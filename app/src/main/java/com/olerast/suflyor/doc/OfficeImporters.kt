package com.olerast.suflyor.doc

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

internal object Zip {
    private const val MAX_ENTRY = 64L * 1024 * 1024

    /** Reads the named entries of a zip archive into memory; other entries are skipped. */
    fun read(bytes: ByteArray, names: Set<String>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name !in names) continue
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY) throw ImportException("Файл внутри архива слишком большой: ${entry.name}")
                    buf.write(chunk, 0, n)
                }
                out[entry.name] = buf.toByteArray()
            }
        }
        return out
    }

    fun entryNames(bytes: ByteArray, limit: Int = 200): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (names.size < limit) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        return names
    }
}

internal object Sax {
    fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }
}

/** DOCX (Word 2007+): paragraphs, headings (by style or outline level) and bold runs; skips deleted and struck text. */
object DocxImporter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"

    fun parse(bytes: ByteArray, title: String): ScriptDocument {
        val parts = Zip.read(bytes, setOf("word/document.xml", "word/styles.xml"))
        val document = parts["word/document.xml"]
            ?: throw ImportException("В архиве нет word/document.xml — это не документ Word")
        val headingStyles = parts["word/styles.xml"]?.let(::headingStyles) ?: emptySet()
        val handler = DocumentHandler(headingStyles)
        Sax.parse(document, handler)
        val warnings = mutableListOf<String>()
        if (handler.tables > 0) {
            warnings += "В документе есть таблицы (${handler.tables}): их ячейки показаны по порядку, как обычный текст."
        }
        return ScriptDocument(title, "DOCX", handler.paragraphs, warnings)
    }

    private fun isOn(attrs: Attributes): Boolean {
        val v = attrs.getValue(W, "val") ?: return true
        return v !in setOf("0", "false", "off", "none")
    }

    /** Style ids that are headings: "heading N" / "Title" by name, or with an outline level. Russian Word uses ids like "1". */
    private fun headingStyles(bytes: ByteArray): Set<String> {
        val result = HashSet<String>()
        Sax.parse(bytes, object : DefaultHandler() {
            var id: String? = null
            var isHeading = false

            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                if (uri != W) return
                when (localName) {
                    "style" -> {
                        id = if (attrs.getValue(W, "type") == "paragraph") attrs.getValue(W, "styleId") else null
                        isHeading = false
                    }
                    "name" -> {
                        val name = attrs.getValue(W, "val")?.lowercase().orEmpty()
                        if (name.startsWith("heading") || name == "title" || name.startsWith("заголовок")) isHeading = true
                    }
                    "outlineLvl" -> if ((attrs.getValue(W, "val")?.toIntOrNull() ?: 9) < 9) isHeading = true
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                if (uri == W && localName == "style") {
                    val styleId = id
                    if (styleId != null && isHeading) result += styleId
                    id = null
                }
            }
        })
        return result
    }

    private class DocumentHandler(private val headingStyles: Set<String>) : DefaultHandler() {
        val paragraphs = mutableListOf<Paragraph>()
        var tables = 0

        private class Frame(val builder: ParagraphBuilder = ParagraphBuilder()) {
            var style: String? = null
            var outline: Int? = null
        }

        private val stack = ArrayDeque<Frame>()
        private var inText = false
        private var inPPr = false
        private var runBold = false
        private var runStruck = false
        private var skipDepth = 0

        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            if (uri == MC && localName == "Fallback") {
                skipDepth++
                return
            }
            if (uri != W) return
            when (localName) {
                "p" -> stack.addLast(Frame())
                "pPr" -> inPPr = true
                "pStyle" -> if (inPPr) stack.lastOrNull()?.style = attrs.getValue(W, "val")
                "outlineLvl" -> if (inPPr) stack.lastOrNull()?.outline = attrs.getValue(W, "val")?.toIntOrNull()
                "r" -> {
                    runBold = false
                    runStruck = false
                }
                "b" -> if (!inPPr) runBold = isOn(attrs)
                "strike", "dstrike" -> if (!inPPr) runStruck = isOn(attrs)
                "t" -> inText = true
                "tab", "br", "cr" -> if (skipDepth == 0) stack.lastOrNull()?.builder?.append(" ")
                "noBreakHyphen" -> if (skipDepth == 0) stack.lastOrNull()?.builder?.append("-")
                "del", "moveFrom" -> skipDepth++
                "tbl" -> tables++
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (uri == MC && localName == "Fallback") {
                skipDepth--
                return
            }
            if (uri != W) return
            when (localName) {
                "t" -> inText = false
                "pPr" -> inPPr = false
                "del", "moveFrom" -> skipDepth--
                "p" -> {
                    val frame = stack.removeLastOrNull() ?: return
                    val style = frame.style
                    val heading = (style != null && (style in headingStyles || style.lowercase().startsWith("heading"))) ||
                        (frame.outline != null && frame.outline!! < 9)
                    frame.builder.build(if (heading) Paragraph.Kind.HEADING else Paragraph.Kind.BODY)?.let { paragraphs += it }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!inText || skipDepth > 0 || runStruck) return
            stack.lastOrNull()?.builder?.append(String(ch, start, length), runBold)
        }
    }
}

/** ODT (LibreOffice / Google Docs export): text:p and text:h, skipping footnotes and comments. */
object OdtImporter {
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"

    fun parse(bytes: ByteArray, title: String): ScriptDocument {
        val content = Zip.read(bytes, setOf("content.xml"))["content.xml"]
            ?: throw ImportException("В архиве нет content.xml — это не документ ODT")
        val paragraphs = mutableListOf<Paragraph>()
        Sax.parse(content, object : DefaultHandler() {
            val stack = ArrayDeque<Pair<ParagraphBuilder, Paragraph.Kind>>()
            var skipDepth = 0

            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                when {
                    uri == TEXT && localName == "note-body" -> skipDepth++
                    uri == OFFICE && localName == "annotation" -> skipDepth++
                    uri == TEXT && (localName == "p" || localName == "h") ->
                        stack.addLast(ParagraphBuilder() to if (localName == "h") Paragraph.Kind.HEADING else Paragraph.Kind.BODY)
                    uri == TEXT && (localName == "s" || localName == "tab" || localName == "line-break") ->
                        if (skipDepth == 0) stack.lastOrNull()?.first?.append(" ")
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                when {
                    uri == TEXT && localName == "note-body" -> skipDepth--
                    uri == OFFICE && localName == "annotation" -> skipDepth--
                    uri == TEXT && (localName == "p" || localName == "h") -> {
                        val (builder, kind) = stack.removeLastOrNull() ?: return
                        builder.build(kind)?.let { paragraphs += it }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (skipDepth == 0) stack.lastOrNull()?.first?.append(String(ch, start, length))
            }
        })
        return ScriptDocument(title, "ODT", paragraphs)
    }
}
