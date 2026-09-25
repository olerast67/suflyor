package com.olerast.suflyor

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import com.olerast.suflyor.data.ScriptRepository
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.doc.PdfTextExtractor
import com.olerast.suflyor.session.SessionEngine

class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var engine: SessionEngine
        private set
    lateinit var scripts: ScriptRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        engine = SessionEngine(this)
        scripts = ScriptRepository(this, settings)
        offerPdfImport()
        DiagLog.i("Приложение запущено, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    /** Lists the app as a PDF handler only on devices that can read PDF text (see the .PdfImport alias). */
    private fun offerPdfImport() {
        val alias = ComponentName(this, "$PACKAGE.PdfImport")
        val want = if (PdfTextExtractor.isSupported()) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            if (packageManager.getComponentEnabledSetting(alias) != want) {
                packageManager.setComponentEnabledSetting(alias, want, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { DiagLog.e("Не удалось настроить открытие PDF", it) }
    }

    companion object {
        /** Code namespace; the debug build's application id has a ".debug" suffix, class names don't. */
        private const val PACKAGE = "com.olerast.suflyor"

        lateinit var instance: App
            private set
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var audioSource: Int
        get() = prefs.getInt("audioSource", MediaRecorder.AudioSource.VOICE_RECOGNITION)
        set(v) = prefs.edit().putInt("audioSource", v).apply()
    var sampleRate: Int
        get() = prefs.getInt("sampleRate", 48000)
        set(v) = prefs.edit().putInt("sampleRate", v).apply()
    var phraseMode: Boolean
        get() = prefs.getBoolean("phraseMode", true)
        set(v) = prefs.edit().putBoolean("phraseMode", v).apply()
    var maxWords: Int
        get() = prefs.getInt("maxWords", 7)
        set(v) = prefs.edit().putInt("maxWords", v).apply()
    var fontSp: Int
        get() = prefs.getInt("fontSp", 26)
        set(v) = prefs.edit().putInt("fontSp", v).apply()
    var overlayLines: Int
        get() = prefs.getInt("overlayLines", 3)
        set(v) = prefs.edit().putInt("overlayLines", v).apply()
    var useHotwords: Boolean
        get() = prefs.getBoolean("useHotwords", true)
        set(v) = prefs.edit().putBoolean("useHotwords", v).apply()
    var autoScrollWpm: Int
        get() = prefs.getInt("autoScrollWpm", 130)
        set(v) = prefs.edit().putInt("autoScrollWpm", v).apply()
    var showDiagnostics: Boolean
        get() = prefs.getBoolean("showDiagnostics2", false)
        set(v) = prefs.edit().putBoolean("showDiagnostics2", v).apply()

    /** While the reader is speaking, show the position this many words ahead of what was recognized (hides ASR latency). */
    var leadWords: Int
        get() = prefs.getInt("leadWords", 1)
        set(v) = prefs.edit().putInt("leadWords", v).apply()
    var wordHighlight: Boolean
        get() = prefs.getBoolean("wordHighlight", false)
        set(v) = prefs.edit().putBoolean("wordHighlight", v).apply()

    /** Background opacity of the floating window, percent. */
    var overlayAlpha: Int
        get() = prefs.getInt("overlayAlpha", 72)
        set(v) = prefs.edit().putInt("overlayAlpha", v).apply()
    var overlayY: Int
        get() = prefs.getInt("overlayY2", 0)
        set(v) = prefs.edit().putInt("overlayY2", v).apply()

    var currentScriptId: String?
        get() = prefs.getString("currentScriptId", null)
        set(v) = prefs.edit().putString("currentScriptId", v).apply()

    /** Which app "Поверх камеры" opens: name of [com.olerast.suflyor.overlay.CameraTarget]. */
    var overlayTarget: String
        get() = prefs.getString("overlayTarget", "INSTAGRAM") ?: "INSTAGRAM"
        set(v) = prefs.edit().putString("overlayTarget", v).apply()

    /** Hardware keys (volume, Bluetooth remote, ring, keyboard) control the prompter during a session. */
    var keyControl: Boolean
        get() = prefs.getBoolean("keyControl", true)
        set(v) = prefs.edit().putBoolean("keyControl", v).apply()

    /** Off: volume keys go to the camera app (e.g. a selfie remote that starts recording with "volume up"). */
    var volumeKeys: Boolean
        get() = prefs.getBoolean("volumeKeys", true)
        set(v) = prefs.edit().putBoolean("volumeKeys", v).apply()

    private var cachedBindings: Map<Int, com.olerast.suflyor.overlay.KeyAction>? = null
    var keyBindings: Map<Int, com.olerast.suflyor.overlay.KeyAction>
        get() = cachedBindings ?: com.olerast.suflyor.overlay.KeyBindings.parse(prefs.getString("keyBindings", null))
            .also { cachedBindings = it }
        set(v) {
            cachedBindings = v
            prefs.edit().putString("keyBindings", com.olerast.suflyor.overlay.KeyBindings.format(v)).apply()
        }

    /** The microphone permission was requested at least once (tells "never asked" from "denied for good"). */
    var micAsked: Boolean
        get() = prefs.getBoolean("micAsked", false)
        set(v) = prefs.edit().putBoolean("micAsked", v).apply()

    /** Seconds of 3-2-1 before timed scrolling starts or resumes; 0 = off. */
    var countdownSec: Int
        get() = prefs.getInt("countdownSec", 3)
        set(v) = prefs.edit().putInt("countdownSec", v).apply()

    /** Turn the floating window with the phone for landscape recording. */
    var autoRotate: Boolean
        get() = prefs.getBoolean("autoRotate", true)
        set(v) = prefs.edit().putBoolean("autoRotate", v).apply()
}
