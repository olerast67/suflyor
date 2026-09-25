package com.olerast.suflyor.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.olerast.suflyor.App
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.session.SessionEngine
import com.olerast.suflyor.session.SessionService

/** Starts and stops the "over other apps" session: foreground service + engine + floating window. */
object OverlayHost {
    private var controller: OverlayController? = null
    private var watching = false

    /** @return null on success, otherwise what the user has to do first. */
    fun startSession(context: Context): String? {
        val a11y = PrompterAccessibilityService.instance
        val canDraw = Settings.canDrawOverlays(context)
        if (a11y == null && !canDraw) {
            return "Включи службу спецвозможностей «Суфлёр» или разреши показ поверх других окон"
        }
        val app = App.instance
        val appContext = context.applicationContext
        context.startForegroundService(Intent(context, SessionService::class.java))
        app.engine.start(SessionEngine.Mode.OVERLAY)
        showWindow(context)
        if (!watching) {
            watching = true
            app.engine.addListener { s ->
                if (s.mode == SessionEngine.Mode.IDLE && !s.starting && controller != null) {
                    hideWindow()
                    appContext.stopService(Intent(appContext, SessionService::class.java))
                    PrompterTileService.requestUpdate(appContext)
                }
            }
        }
        PrompterTileService.requestUpdate(appContext)
        return null
    }

    fun stopSession(context: Context) {
        App.instance.engine.stop()
        hideWindow()
        context.applicationContext.stopService(Intent(context, SessionService::class.java))
        PrompterTileService.requestUpdate(context.applicationContext)
    }

    private fun showWindow(context: Context) {
        hideWindow()
        val a11y = PrompterAccessibilityService.instance
        controller = if (a11y != null) {
            DiagLog.i("Окно поверх: через службу спецвозможностей (TYPE_ACCESSIBILITY_OVERLAY)")
            OverlayController(a11y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else {
            DiagLog.i("Окно поверх: обычное плавающее окно (TYPE_APPLICATION_OVERLAY), спецвозможности выключены")
            OverlayController(context.applicationContext, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        runCatching { controller?.show() }.onFailure {
            DiagLog.e("Не удалось показать окно поверх", it)
            controller = null
        }
    }

    fun hideWindow() {
        controller?.hide()
        controller = null
    }

    /** Re-creates the window with the right host when the accessibility service connects or disconnects mid-session. */
    fun onHostChanged(context: Context) {
        if (controller != null && App.instance.engine.state.mode == SessionEngine.Mode.OVERLAY) showWindow(context)
    }
}

/**
 * Accessibility service of the app. It shows nothing by itself; its only jobs are to make Android treat our
 * microphone capture as an accessibility capture (allowed concurrently with a camera app, CDD 5.4.5),
 * host the floating window as a trusted accessibility overlay, report which app is on screen,
 * and let volume keys / a Bluetooth remote control the prompter during a session.
 */
class PrompterAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        DiagLog.i("Служба спецвозможностей подключена")
        OverlayHost.onHostChanged(this)
        stateListeners.forEach { it() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        App.instance.engine.setForegroundApp(pkg)
    }

    /** Volume keys, Bluetooth selfie remotes, clickers, rings and keyboards control the prompter during a session. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val app = App.instance
        val s = app.settings
        if (app.engine.state.mode != SessionEngine.Mode.OVERLAY || !s.keyControl) return false
        if (KeyBindings.isVolume(event.keyCode) && !s.volumeKeys) return false
        val action = s.keyBindings[event.keyCode] ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            action.perform(app.engine)
            if (event.device?.isVirtual == false && !KeyBindings.isVolume(event.keyCode)) {
                DiagLog.i("Кнопка «${KeyBindings.keyName(event.keyCode)}» (${event.device?.name}): ${action.label}")
            }
        }
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
