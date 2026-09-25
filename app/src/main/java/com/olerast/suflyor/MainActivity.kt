package com.olerast.suflyor

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.doc.DocumentImporter
import com.olerast.suflyor.doc.ImportError
import com.olerast.suflyor.doc.ImportException
import com.olerast.suflyor.doc.MarkdownImporter
import com.olerast.suflyor.doc.PdfTextExtractor
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.overlay.CameraTarget
import com.olerast.suflyor.overlay.OverlayHost
import com.olerast.suflyor.overlay.PrompterAccessibilityService
import com.olerast.suflyor.session.SessionEngine
import com.olerast.suflyor.ui.EditorScreen
import com.olerast.suflyor.ui.JournalScreen
import com.olerast.suflyor.ui.KeyLearning
import com.olerast.suflyor.ui.LibraryScreen
import com.olerast.suflyor.ui.Readiness
import com.olerast.suflyor.ui.RehearsalScreen
import com.olerast.suflyor.ui.ScriptScreen
import com.olerast.suflyor.ui.SettingsScreen
import com.olerast.suflyor.ui.SuflyorTheme
import com.olerast.suflyor.ui.text
import com.olerast.suflyor.ui.toEditableText
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val app get() = App.instance

    private sealed interface Screen {
        data object Library : Screen
        data class Script(val id: String) : Screen
        data class Editor(val id: String?) : Screen
        data object Rehearsal : Screen
        data object Settings : Screen
        data object Journal : Screen
    }

    private val backStack = mutableStateListOf<Screen>(Screen.Library)
    private var readiness by mutableStateOf<Readiness?>(null)
    private var afterMicGranted: (() -> Unit)? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importUri) }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val r = Readiness.check(this)
        readiness = r
        // A second refusal (the rationale was offered before, not any more) is "don't ask again". A first dialog
        // closed with Back also leaves no rationale, but the dialog simply shows again next time: left alone.
        val refusedForGood = micRationaleBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (r.mic) {
            afterMicGranted?.invoke()
        } else if (result.containsKey(Manifest.permission.RECORD_AUDIO) && refusedForGood) {
            openMicSettings()
        }
        afterMicGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is always dark: light system-bar icons regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList(KEY_BACK_STACK)?.let { saved ->
            val restored = saved.mapNotNull(::decodeScreen)
            if (restored.isNotEmpty()) {
                backStack.clear()
                backStack.addAll(restored)
            }
        }
        readiness = Readiness.check(this)
        setContent { SuflyorTheme { Root() } }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_BACK_STACK, ArrayList(backStack.map(::encodeScreen)))
    }

    override fun onStart() {
        super.onStart()
        // Rehearsal stops when the app goes to the background (onStop); resume listening when it comes back.
        if (backStack.lastOrNull() == Screen.Rehearsal && app.engine.state.mode == SessionEngine.Mode.IDLE &&
            Readiness.check(this).mic
        ) {
            app.engine.start(SessionEngine.Mode.IN_APP)
        }
    }

    private fun encodeScreen(s: Screen): String = when (s) {
        Screen.Library -> "library"
        is Screen.Script -> "script:${s.id}"
        is Screen.Editor -> "editor:${s.id.orEmpty()}"
        Screen.Rehearsal -> "rehearsal"
        Screen.Settings -> "settings"
        Screen.Journal -> "journal"
    }

    private fun decodeScreen(s: String): Screen? = when {
        s == "library" -> Screen.Library
        s.startsWith("script:") -> Screen.Script(s.removePrefix("script:"))
        s.startsWith("editor:") -> Screen.Editor(s.removePrefix("editor:").ifEmpty { null })
        s == "rehearsal" -> Screen.Rehearsal
        s == "settings" -> Screen.Settings
        s == "journal" -> Screen.Journal
        else -> null
    }

    private val a11yListener: () -> Unit = { readiness = Readiness.check(this) }

    override fun onResume() {
        super.onResume()
        readiness = Readiness.check(this)
        PrompterAccessibilityService.stateListeners += a11yListener
    }

    override fun onPause() {
        super.onPause()
        PrompterAccessibilityService.stateListeners -= a11yListener
    }

    override fun onStop() {
        super.onStop()
        if (app.engine.state.mode == SessionEngine.Mode.IN_APP) app.engine.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** While a remote button is being assigned in Settings, the next key press goes there. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyLearning.action != null && event.keyCode != KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) KeyLearning.offer(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- navigation ---------------------------------------------------------------------------------------------

    private fun push(s: Screen) {
        backStack.add(s)
    }

    private fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    private fun openScript(id: String) {
        app.scripts.select(id)
        backStack.clear()
        backStack.add(Screen.Library)
        backStack.add(Screen.Script(id))
    }

    @Composable
    private fun Root() {
        val r = readiness ?: Readiness.check(this)
        val screen = backStack.last()
        BackHandler(enabled = backStack.size > 1) { pop() }
        key(screen) {
            when (screen) {
                Screen.Library -> LibraryScreen(
                    readiness = r,
                    onOpen = { id ->
                        app.scripts.select(id)
                        push(Screen.Script(id))
                    },
                    onSettings = { push(Screen.Settings) },
                    onPickFile = { pickFile.launch(MIME_TYPES) },
                    onPaste = ::importClipboard,
                    onWrite = { push(Screen.Editor(null)) },
                )
                is Screen.Script -> ScriptScreen(
                    readiness = r,
                    onBack = ::pop,
                    onEdit = { push(Screen.Editor(screen.id)) },
                    onRehearse = { withMic { push(Screen.Rehearsal) } },
                    onStartOverlay = { target -> withMic { startOverlay(target) } },
                    onFixReadiness = { push(Screen.Settings) },
                    onDelete = {
                        app.scripts.delete(screen.id)
                        pop()
                    },
                )
                is Screen.Editor -> {
                    val doc = screen.id?.let { app.scripts.documentOf(it) }
                    EditorScreen(
                        isNew = screen.id == null,
                        initialTitle = doc?.title.orEmpty(),
                        initialText = doc?.toEditableText().orEmpty(),
                        onCancel = ::pop,
                        onSave = { title, text -> saveEditor(screen.id, doc, title, text) },
                    )
                }
                Screen.Rehearsal -> RehearsalScreen(onBack = ::pop)
                Screen.Settings -> SettingsScreen(
                    readiness = r,
                    onBack = ::pop,
                    onRequestPermissions = ::requestRuntimePermissions,
                    onJournal = { push(Screen.Journal) },
                )
                Screen.Journal -> JournalScreen(onBack = ::pop)
            }
        }
    }

    // ---- actions ------------------------------------------------------------------------------------------------

    private var micRationaleBefore = false

    private fun requestRuntimePermissions() {
        val mic = Manifest.permission.RECORD_AUDIO
        micRationaleBefore = shouldShowRequestPermissionRationale(mic)
        // Asked before, not granted, no rationale: denied for good, the system would answer without a dialog.
        if (!Readiness.check(this).mic && app.settings.micAsked && !micRationaleBefore) {
            afterMicGranted = null
            openMicSettings()
            return
        }
        app.settings.micAsked = true
        val perms = mutableListOf(mic)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(perms.toTypedArray())
    }

    /** Only app settings can bring the microphone back once it is denied for good. */
    private fun openMicSettings() {
        toast(getString(R.string.main_mic_blocked))
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun withMic(action: () -> Unit) {
        if (Readiness.check(this).mic) {
            action()
        } else {
            afterMicGranted = action
            requestRuntimePermissions()
        }
    }

    private fun startOverlay(target: CameraTarget) {
        if (app.engine.state.mode == SessionEngine.Mode.IN_APP) app.engine.stop()
        val problem = OverlayHost.startSession(this)
        if (problem != null) {
            toast(getString(problem.message))
            push(Screen.Settings)
            return
        }
        val launch = target.launchIntent(this) ?: return
        runCatching { startActivity(launch) }.onFailure { toast(getString(R.string.main_open_app_failed, getString(target.label))) }
    }

    private fun saveEditor(id: String?, old: ScriptDocument?, title: String, text: String) {
        val format = old?.format ?: ScriptDocument.FORMAT_TEXT
        val doc = try {
            DocumentImporter.checkSize(MarkdownImporter.parse(text, title).copy(title = title, format = format))
        } catch (e: ImportException) {
            toast(e.error.text(this))
            return
        }
        if (doc.isEmpty) {
            toast(getString(R.string.main_no_words))
            return
        }
        if (id == null) {
            openScript(app.scripts.add(doc))
        } else {
            app.scripts.update(id, doc)
            pop()
        }
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        // Relaunching the task from Recents replays the original share: do not import it a second time.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        runCatching {
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.let(::importUri)
                Intent.ACTION_SEND -> {
                    val stream = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    when {
                        stream != null -> importUri(stream)
                        !text.isNullOrBlank() -> importText(
                            text,
                            intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: getString(R.string.import_title_shared),
                        )
                        else -> toast(getString(R.string.import_nothing))
                    }
                }
            }
        }.onFailure { DiagLog.e("Couldn't accept data from another app", it) }
    }

    private fun importText(text: String, title: String) {
        importInBackground { DocumentImporter.checkSize(DocumentImporter.fromPlainText(text, title)) }
    }

    /** Parsing can take a moment on a long text: never on the main thread. */
    private fun importInBackground(parse: () -> ScriptDocument) {
        Thread {
            val result = runCatching(parse)
            runOnUiThread {
                result.onSuccess(::addScript).onFailure {
                    if (it is ImportException) {
                        DiagLog.e("Import failed: ${it.error}")
                        toast(it.error.text(this))
                    } else {
                        DiagLog.e("Import failed: couldn't read the file", it)
                        val detail = it.message
                        toast(
                            if (detail != null) getString(R.string.import_error_read_failed, detail)
                            else getString(R.string.import_error_generic),
                        )
                    }
                }
            }
        }.start()
    }

    private fun addScript(doc: ScriptDocument) {
        openScript(app.scripts.add(doc))
        DiagLog.i("Script: “${doc.title}” (${doc.format}), ${doc.paragraphs.size} paragraphs, ${app.scripts.model.spokenTokens} words")
    }

    private fun importUri(uri: Uri) {
        // Only documents handed over by other apps. A file:// or android.resource:// URI from an intent would make
        // this app read with its own permissions — including its private files.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            toast(ImportError.CannotOpen.text(this))
            return
        }
        toast(getString(R.string.import_opening))
        val untitled = getString(R.string.common_untitled)
        importInBackground {
            val name = queryName(uri)
            val mime = contentResolver.getType(uri)
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > DocumentImporter.MAX_BYTES) throw ImportException(ImportError.FileTooBig(DocumentImporter.MAX_MB))
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: throw ImportException(ImportError.CannotOpen)
            DocumentImporter.import(bytes, name, mime, untitled) { b, t -> PdfTextExtractor.extract(this, b, t) }
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun importClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast(getString(R.string.import_clipboard_empty))
            return
        }
        importText(text, getString(R.string.import_title_clipboard))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_BACK_STACK = "backStack"

        /** PDF is offered in the picker only where the platform can extract its text. */
        private val MIME_TYPES: Array<String>
            get() = listOfNotNull(
                "text/*",
                "application/pdf".takeIf { PdfTextExtractor.isSupported() },
                "application/rtf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.oasis.opendocument.text", "application/octet-stream",
            ).toTypedArray()
    }
}
