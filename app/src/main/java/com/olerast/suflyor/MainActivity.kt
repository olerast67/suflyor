package com.olerast.suflyor

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
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

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val r = Readiness.check(this)
        readiness = r
        if (r.mic) afterMicGranted?.invoke()
        afterMicGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readiness = Readiness.check(this)
        setContent { SuflyorTheme { Root() } }
        if (savedInstanceState == null) handleIncoming(intent)
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

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(perms.toTypedArray())
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
            toast(problem)
            push(Screen.Settings)
            return
        }
        val launch = target.launchIntent(this) ?: return
        runCatching { startActivity(launch) }.onFailure { toast("Не получилось открыть ${target.label}") }
    }

    private fun saveEditor(id: String?, old: ScriptDocument?, title: String, text: String) {
        val doc = MarkdownImporter.parse(text, title).copy(title = title, format = old?.format ?: "Текст")
        if (doc.isEmpty) {
            toast("В тексте нет слов")
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
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::importUri)
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> importUri(stream)
                    !text.isNullOrBlank() -> addScript(
                        DocumentImporter.fromPlainText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Из «Поделиться»"),
                    )
                }
            }
        }
    }

    private fun addScript(doc: ScriptDocument) {
        openScript(app.scripts.add(doc))
        DiagLog.i("Сценарий: «${doc.title}» (${doc.format}), абзацев ${doc.paragraphs.size}, слов ${app.scripts.model.spokenTokens}")
    }

    private fun importUri(uri: Uri) {
        toast("Открываю…")
        Thread {
            val result = runCatching {
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
                        if (total > DocumentImporter.MAX_BYTES) throw ImportException("Файл слишком большой")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: throw ImportException("Не удалось открыть файл")
                DocumentImporter.import(bytes, name, mime) { b, t -> PdfTextExtractor.extract(this, b, t) }
            }
            runOnUiThread {
                result.onSuccess(::addScript).onFailure {
                    val msg = if (it is ImportException) it.message else "Не получилось прочитать файл: ${it.message}"
                    DiagLog.e("Импорт: $msg", if (it is ImportException) null else it)
                    toast(msg ?: "Ошибка импорта")
                }
            }
        }.start()
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
            toast("В буфере нет текста")
            return
        }
        addScript(DocumentImporter.fromPlainText(text, "Из буфера"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val MIME_TYPES = arrayOf(
            "text/*", "application/pdf", "application/rtf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text", "application/octet-stream",
        )
    }
}
