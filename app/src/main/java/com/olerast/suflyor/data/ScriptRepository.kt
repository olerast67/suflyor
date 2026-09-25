package com.olerast.suflyor.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olerast.suflyor.App
import com.olerast.suflyor.Settings
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.ScriptModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Library of scripts, one JSON file per script in filesDir/scripts. Keeps the imported paragraphs (so layout
 * settings can change later) and the layout of the current script, which the session engine follows.
 * List and selection are Compose state, so screens update by themselves.
 */
class ScriptRepository(context: Context, private val settings: Settings) {
    data class Meta(
        val id: String,
        val title: String,
        val format: String,
        val words: Int,
        val updatedAt: Long,
    )

    private val dir = File(context.filesDir, "scripts").apply { mkdirs() }
    private val legacyFile = File(context.filesDir, "script.json")

    var items by mutableStateOf<List<Meta>>(emptyList())
        private set
    var currentId by mutableStateOf<String?>(null)
        private set

    var document: ScriptDocument = ScriptDocument("", "", emptyList())
        private set
    var model: ScriptModel = ScriptModel.EMPTY
        private set

    /** Bumped whenever [model] is rebuilt, so screens showing the current script recompose. */
    var layoutVersion by mutableIntStateOf(0)
        private set

    init {
        migrateLegacy()
        items = loadAllMeta()
        if (items.isEmpty()) add(sample())
        val last = settings.currentScriptId?.takeIf { id -> items.any { it.id == id } } ?: items.first().id
        select(last)
    }

    fun select(id: String) {
        val doc = read(id) ?: return
        currentId = id
        settings.currentScriptId = id
        document = doc
        relayout()
    }

    /** Adds a script and makes it current. */
    fun add(doc: ScriptDocument): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        write(id, doc, createdAt = System.currentTimeMillis())
        items = loadAllMeta()
        select(id)
        return id
    }

    fun update(id: String, doc: ScriptDocument) {
        val created = runCatching { JSONObject(file(id).readText()).optLong("createdAt") }.getOrDefault(System.currentTimeMillis())
        write(id, doc, created)
        items = loadAllMeta()
        if (id == currentId) {
            document = doc
            relayout()
        }
    }

    fun delete(id: String) {
        file(id).delete()
        items = loadAllMeta()
        if (items.isEmpty()) add(sample())
        if (id == currentId) select(items.first().id)
    }

    fun documentOf(id: String): ScriptDocument? = if (id == currentId) document else read(id)

    /** Rebuilds the layout of the current script after layout settings change and hands it to the engine. */
    fun relayout() {
        model = ScriptLayout.build(document, settings.phraseMode, settings.maxWords)
        App.instance.engine.setScript(model)
        layoutVersion++
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun write(id: String, doc: ScriptDocument, createdAt: Long) {
        val words = ScriptLayout.build(doc, phraseMode = false).spokenTokens
        val json = JSONObject().apply {
            put("id", id)
            put("title", doc.title)
            put("format", doc.format)
            put("createdAt", createdAt)
            put("updatedAt", System.currentTimeMillis())
            put("words", words)
            put("warnings", JSONArray(doc.warnings))
            put("paragraphs", JSONArray().apply {
                doc.paragraphs.forEach { p ->
                    put(JSONObject().apply {
                        put("text", p.text)
                        put("kind", p.kind.name)
                        put("em", JSONArray().apply { p.emphasis.forEach { put(it.first); put(it.last) } })
                    })
                }
            })
        }
        runCatching { file(id).writeText(json.toString()) }.onFailure { DiagLog.e("Не удалось сохранить сценарий", it) }
    }

    private fun read(id: String): ScriptDocument? = runCatching { parse(JSONObject(file(id).readText())) }.getOrNull()

    private fun parse(o: JSONObject): ScriptDocument {
        val arr = o.getJSONArray("paragraphs")
        val paragraphs = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val em = p.optJSONArray("em") ?: JSONArray()
            Paragraph(
                p.getString("text"),
                runCatching { Paragraph.Kind.valueOf(p.optString("kind", "BODY")) }.getOrDefault(Paragraph.Kind.BODY),
                (0 until em.length() / 2).map { k -> em.getInt(2 * k)..em.getInt(2 * k + 1) },
            )
        }
        val warnings = o.optJSONArray("warnings")?.let { w -> (0 until w.length()).map { w.getString(it) } } ?: emptyList()
        return ScriptDocument(o.optString("title", "Сценарий"), o.optString("format", "?"), paragraphs, warnings)
    }

    private fun loadAllMeta(): List<Meta> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                Meta(f.nameWithoutExtension, o.optString("title"), o.optString("format"), o.optInt("words"), o.optLong("updatedAt"))
            }.getOrNull()
        }
        .sortedByDescending { it.updatedAt }

    private fun migrateLegacy() {
        if (!legacyFile.exists()) return
        runCatching {
            val doc = parse(JSONObject(legacyFile.readText()))
            if (!doc.isEmpty && doc.title != "Пример") {
                write(UUID.randomUUID().toString().substring(0, 8), doc, System.currentTimeMillis())
            }
        }
        legacyFile.delete()
    }

    companion object {
        fun sample() = ScriptDocument(
            "Пример",
            "встроенный",
            listOf(
                Paragraph("Пример сценария", Paragraph.Kind.HEADING),
                Paragraph(
                    "Привет! Сегодня я покажу, как суфлёр следит за голосом и сам прокручивает текст, " +
                        "чтобы не приходилось за ним бегать.",
                ),
                Paragraph(
                    "Прочитай пару строк, посмотри в камеру, потом снова загляни в текст. " +
                        "Если собьёшься и начнёшь фразу заново, текст вернётся к её началу.",
                    emphasis = listOf(0..22),
                ),
                Paragraph("[Пауза, улыбка] А если замолчишь, текст просто подождёт тебя."),
                Paragraph(
                    "Попробуй прочитать этот абзац поверх Instagram, TikTok или камеры — " +
                        "суфлёр будет слышать тебя и во время записи.",
                ),
            ),
        )
    }
}
