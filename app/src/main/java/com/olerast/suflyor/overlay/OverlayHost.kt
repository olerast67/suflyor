package com.olerast.suflyor.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.StringRes
import com.olerast.suflyor.App
import com.olerast.suflyor.R
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.session.SessionEngine
import com.olerast.suflyor.session.SessionService

/** Why a session over other apps could not start; [message] tells the user what to do. */
enum class StartProblem(@StringRes val message: Int) {
    NO_HOST(R.string.start_problem_no_host),
    WINDOW_FAILED(R.string.start_problem_window_failed),
}

/** Starts and stops the "over other apps" session: foreground service + engine + floating window. */
object OverlayHost {
    private var controller: OverlayController? = null
    private var watching = false

    /** A session over other apps is running (independent of whether its window currently exists). */
    var active = false
        private set

    private var screenOffReceiver: BroadcastReceiver? = null

    /** @return null on success, otherwise what the user has to do first. */
    fun startSession(context: Context): StartProblem? {
        val a11y = PrompterAccessibilityService.instance
        val canDraw = Settings.canDrawOverlays(context)
        if (a11y == null && !canDraw) {
            return StartProblem.NO_HOST
        }
        val app = App.instance
        val appContext = context.applicationContext
        // Already running (second tap, tile + app): keep the service, microphone and window; just make sure the
        // window is there. Restarting would publish a transient IDLE and tear the foreground service down.
        if (active && app.engine.state.mode == SessionEngine.Mode.OVERLAY) {
            if (controller == null) showOrStop(context)
            PrompterTileService.requestUpdate(appContext)
            return null
        }
        if (!watching) {
            watching = true
            app.engine.addListener { s ->
                if (active && s.mode == SessionEngine.Mode.IDLE && !s.starting) endSession(appContext)
            }
        }
        // Start the engine first: stopping a running rehearsal publishes IDLE, which must not end this new session.
        app.engine.start(SessionEngine.Mode.OVERLAY)
        active = true
        // The window first: if it can't be shown, nothing else (foreground service, key filtering) is started.
        if (!showWindow(context)) {
            stopSession(context)
            return StartProblem.WINDOW_FAILED
        }
        context.startForegroundService(Intent(context, SessionService::class.java))
        watchScreen(appContext)
        PrompterAccessibilityService.instance?.onSessionChanged(true)
        PrompterTileService.requestUpdate(appContext)
        return null
    }

    fun stopSession(context: Context) {
        App.instance.engine.stop()
        endSession(context.applicationContext)
    }

    /** Idempotent cleanup after the engine went idle, whoever stopped it. */
    private fun endSession(appContext: Context) {
        active = false
        hideWindow()
        screenOffReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        screenOffReceiver = null
        // A service that hasn't reached startForeground yet must not be stopped from outside (the system would
        // kill the app); its pending onStartCommand sees the session is over and stops itself.
        SessionService.stop(appContext)
        PrompterAccessibilityService.instance?.onSessionChanged(false)
        PrompterTileService.requestUpdate(appContext)
    }

    /**
     * Screen off = the take is over (camera apps keep the screen on while recording). Stop listening instead of
     * leaving the microphone open with the prompter over the lock screen.
     */
    private fun watchScreen(appContext: Context) {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF && active) {
                    DiagLog.i("Экран выключен — сессия остановлена")
                    stopSession(appContext)
                }
            }
        }
        appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        screenOffReceiver = receiver
    }

    /** @return whether the prompter window is on screen now. */
    private fun showWindow(context: Context): Boolean {
        hideWindow()
        val a11y = PrompterAccessibilityService.instance
        val next = when {
            a11y != null -> {
                DiagLog.i("Окно поверх: через службу спецвозможностей (TYPE_ACCESSIBILITY_OVERLAY)")
                OverlayController(a11y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            }
            Settings.canDrawOverlays(context) -> {
                DiagLog.i("Окно поверх: обычное плавающее окно (TYPE_APPLICATION_OVERLAY), спецвозможности выключены")
                OverlayController(context.applicationContext, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            else -> null
        }
        val shown = next != null && runCatching { next.show() }.onFailure { DiagLog.e("Не удалось показать окно поверх", it) }.isSuccess
        if (shown) controller = next
        return shown
    }

    /** Mid-session window re-creation. */
    private fun showOrStop(context: Context) {
        if (showWindow(context) || !active) return
        // No way to show the prompter any more (e.g. the accessibility service was turned off mid-session and
        // there is no overlay permission): do not keep the microphone running invisibly.
        Toast.makeText(context.applicationContext, "Окно суфлёра закрыто: служба спецвозможностей выключена", Toast.LENGTH_LONG).show()
        stopSession(context)
    }

    fun hideWindow() {
        controller?.hide()
        controller = null
    }

    /** Re-creates the window with the right host when the accessibility service connects or disconnects mid-session. */
    fun onHostChanged(context: Context) {
        if (active && App.instance.engine.state.mode == SessionEngine.Mode.OVERLAY) showOrStop(context)
    }
}

/**
 * Accessibility service of the app. It shows nothing by itself; its jobs are to make Android treat our microphone
 * capture as an accessibility capture (allowed concurrently with a camera app, CDD 5.4.5), host the floating window
 * as a trusted accessibility overlay, and — only while a session runs over another app — report which app is on
 * screen and let bound hardware keys (volume, Bluetooth remote, keyboard) control the prompter.
 * It never reads window content.
 */
class PrompterAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        DiagLog.i("Служба спецвозможностей подключена")
        onSessionChanged(OverlayHost.active)
        OverlayHost.onHostChanged(this)
        stateListeners.forEach { it() }
    }

    /** Listen to window changes and filter keys only during a session; stay deaf otherwise. */
    fun onSessionChanged(active: Boolean) {
        val info = serviceInfo ?: return
        val keys = active && App.instance.settings.keyControl
        info.eventTypes = if (active) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
        info.flags = if (keys) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        runCatching { serviceInfo = info }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!OverlayHost.active || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        App.instance.engine.setForegroundApp(pkg)
    }

    /** Volume keys, Bluetooth selfie remotes, clickers, rings and keyboards control the prompter during a session. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val app = App.instance
        val s = app.settings
        if (!OverlayHost.active || app.engine.state.mode != SessionEngine.Mode.OVERLAY || !s.keyControl) return false
        if (KeyBindings.isVolume(event.keyCode) && !s.volumeKeys) return false
        val action = s.keyBindings[event.keyCode] ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action.perform(app.engine)
        return true
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        DiagLog.i("Служба спецвозможностей отключена")
        OverlayHost.onHostChanged(applicationContext)
        stateListeners.forEach { it() }
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var instance: PrompterAccessibilityService? = null
            private set

        /** Called on the main thread when the service connects or disconnects (the app's readiness changes). */
        val stateListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, PrompterAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
