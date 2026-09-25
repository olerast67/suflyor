package com.olerast.suflyor.doc

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

internal object Zip {
    /** A document part we read into memory (word/document.xml, content.xml). Pictures are only skipped over. */
    private const val MAX_ENTRY = 32L * 1024 * 1024

    /** All entries of one archive together may inflate to at most this much (zip bombs). */
    private const val MAX_TOTAL = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000

    /**
     * Reads the named entries of a zip archive into memory; stops as soon as all of them are found. When skipping
     * other entries (pictures) uses up the budget, it returns what it has: the caller decides whether the document
     * part it needs is there (Word stores word/media before the optional word/styles.xml).
     */
    fun read(bytes: ByteArray, names: Set<String>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var budget = MAX_TOTAL
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            entries@ while (out.size < names.size) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw ImportException("В архиве слишком много файлов")
                val wanted = entry.name in names
                val buf = if (wanted) ByteArrayOutputStream() else null
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    total += n
                    budget -= n
                    if (wanted && (total > MAX_ENTRY || budget < 0)) {
                        throw ImportException("Архив распаковывается в слишком большой объём")
                    }
                    if (budget < 0) break@entries
                    buf?.write(chunk, 0, n)
                }
                if (buf != null) out[entry.name] = buf.toByteArray()
            }
        }
        return out
    }

    /** Entry names, reading only the local headers (entry data is skipped without inflating when possible). */
    fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        var budget = MAX_TOTAL
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val chunk = ByteArray(64 * 1024)
            while (names.size < MAX_ENTRIES) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                if (entry.name == "word/document.xml" || entry.name == "content.xml") break
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    budget -= n
                    if (budget < 0) return names
                }
            }
        }
        return names
    }
}

internal object Sax {
    private const val UNSAFE = "Документ повреждён или небезопасен"

    private class DtdRejected : SAXException("DTD")

    /** Tests: behave like Android, where the disallow-doctype-decl feature doesn't exist and only NoDtd guards. */
    internal var onlyLexicalGuard = false

    /** Office XML never contains a DTD; one here can only be an entity-expansion attack ("billion laughs"). */
    private object NoDtd : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw DtdRejected()
        }
    }

    fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) {
            throw ImportException(UNSAFE)
        }
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // Android's parser (Expat) ignores these; the lexical handler below is what stops a DTD there.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        if (!onlyLexicalGuard) runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val parser = factory.newSAXParser()
        val guarded = runCatching { parser.setProperty("http://xml.org/sax/properties/lexical-handler", NoDtd) }.isSuccess
        // Without the guard, at least refuse what the text check above can't see: UTF-16 or a DTD after a long prolog.
        if (!guarded && (bytes.indexOf(0.toByte()) >= 0 || String(bytes, Charsets.ISO_8859_1).contains("<!DOCTYPE", ignoreCase = true))) {
            throw ImportException(UNSAFE)
        }
        try {
            parser.parse(ByteArrayInputStream(bytes), handler)
        } catch (e: SAXException) {
            // The JVM parser wraps exceptions thrown by our handlers (the text length limit).
            (e.exception as? ImportException)?.let { throw it }
            // DtdRejected from our guard (Android), or the JVM parser's own disallow-doctype-decl error.
            if (e is DtdRejected || e.exception is DtdRejected || e.message.orEmpty().contains("DOCTYPE")) {
                throw ImportException(UNSAFE)
            }
            throw e
        }
    }
}

/** Paragraphs inside paragraphs (text boxes) are real but shallow; a crafted file could nest millions. */
internal const val MAX_NESTED_PARAGRAPHS = 32

/**
 * Stops a small, highly compressed document from unpacking into hundreds of megabytes of text before the length
 * check runs: the whole import may not produce more than twice the script limit.
 */
internal class CharBudget(private var left: Int = DocumentImporter.MAX_CHARS * 2) {
    fun take(n: Int) {
        left -= n
        if (left < 0) {
            throw ImportException("Текст слишком длинный: суфлёр принимает до ${DocumentImporter.MAX_CHARS / 1000} тыс. знаков")
        }
    }
}

/** DOCX (Word 2007+): paragraphs, headings (by style or outline level) and bold runs; skips deleted and struck text. */
object DocxImporter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** "Strict" OOXML, written by Word when saving as "Strict Open XML Document". */
    private const val W_STRICT = "http://purl.oclc.org/ooxml/wordprocessingml/main"
    private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"

    private fun isW(uri: String) = uri == W || uri == W_STRICT

    /** Tracked formatting changes keep the *old* formatting inside; it must not override the current one. */
    private val FORMAT_CHANGES = setOf(
        "rPrChange", "pPrChange", "sectPrChange", "tblPrChange", "trPrChange", "tcPrChange", "tblGridChange",
        "numberingChange", "tblPrExChange",
    )

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

    private fun attr(attrs: Attributes, name: String): String? = attrs.getValue(W, name) ?: attrs.getValue(W_STRICT, name)

    private fun isOn(attrs: Attributes): Boolean {
        val v = attr(attrs, "val") ?: return true
        return v !in setOf("0", "false", "off", "none")
    }

    /** Style ids that are headings: "heading N" / "Title" by name, or with an outline level. Russian Word uses ids like "1". */
    private fun headingStyles(bytes: ByteArray): Set<String> {
        val result = HashSet<String>()
        Sax.parse(bytes, object : DefaultHandler() {
            var id: String? = null
            var isHeading = false

            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                if (!isW(uri)) return
                when (localName) {
                    "style" -> {
                        id = if (attr(attrs, "type") == "paragraph") attr(attrs, "styleId") else null
                        isHeading = false
                    }
                    "name" -> {
                        val name = attr(attrs, "val")?.lowercase().orEmpty()
                        if (name.startsWith("heading") || name == "title" || name.startsWith("заголовок")) isHeading = true
                    }
                    "outlineLvl" -> if ((attr(attrs, "val")?.toIntOrNull() ?: 9) < 9) isHeading = true
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                if (isW(uri) && localName == "style") {
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
        private var changeDepth = 0
        private var pDepth = 0

        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            if (uri == MC && localName == "Fallback") {
                skipDepth++
                return
            }
            if (!isW(uri)) return
            if (localName in FORMAT_CHANGES) {
                changeDepth++
                return
            }
            if (changeDepth > 0) return
            when (localName) {
                // Deeper paragraphs (only a crafted file nests them this far) join the innermost one.
                "p" -> if (++pDepth <= MAX_NESTED_PARAGRAPHS) stack.addLast(Frame())
                "pPr" -> inPPr = true
                "pStyle" -> if (inPPr) stack.lastOrNull()?.style = attr(attrs, "val")
                "outlineLvl" -> if (inPPr) stack.lastOrNull()?.outline = attr(attrs, "val")?.toIntOrNull()
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
            if (!isW(uri)) return
            if (localName in FORMAT_CHANGES) {
                changeDepth--
                return
            }
            if (changeDepth > 0) return
            when (localName) {
                "t" -> inText = false
                "pPr" -> inPPr = false
                "del", "moveFrom" -> skipDepth--
                "p" -> {
                    if (pDepth-- > MAX_NESTED_PARAGRAPHS) return
                    val frame = stack.removeLastOrNull() ?: return
                    val style = frame.style
                    val heading = (style != null && (style in headingStyles || style.lowercase().startsWith("heading"))) ||
                        (frame.outline != null && frame.outline!! < 9)
                    frame.builder.build(if (heading) Paragraph.Kind.HEADING else Paragraph.Kind.BODY)?.let { paragraphs += it }
                }
            }
        }

        private val budget = CharBudget()

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!inText || skipDepth > 0 || runStruck) return
            budget.take(length)
            stack.lastOrNull()?.builder?.append(String(ch, start, length), runBold)
        }
    }
}

/** ODT (LibreOffice / Google Docs export): text:p and text:h, skipping footnotes, comments and tracked deletions. */
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
            var depth = 0

            // Deleted text of tracked changes lives in text:tracked-changes; inserted text is already inline.
            private fun skipped(uri: String, localName: String) =
                (uri == TEXT && (localName == "note-body" || localName == "tracked-changes")) ||
                    (uri == OFFICE && localName == "annotation")

            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                when {
                    skipped(uri, localName) -> skipDepth++
                    uri == TEXT && (localName == "p" || localName == "h") -> if (++depth <= MAX_NESTED_PARAGRAPHS) {
                        stack.addLast(ParagraphBuilder() to if (localName == "h") Paragraph.Kind.HEADING else Paragraph.Kind.BODY)
                    }
                    uri == TEXT && (localName == "s" || localName == "tab" || localName == "line-break") ->
                        if (skipDepth == 0) stack.lastOrNull()?.first?.append(" ")
                }
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                when {
                    skipped(uri, localName) -> skipDepth--
                    uri == TEXT && (localName == "p" || localName == "h") -> {
                        if (depth-- > MAX_NESTED_PARAGRAPHS) return
                        val (builder, kind) = stack.removeLastOrNull() ?: return
                        builder.build(kind)?.let { paragraphs += it }
                    }
                }
            }

            val budget = CharBudget()

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (skipDepth > 0) return
                budget.take(length)
                stack.lastOrNull()?.first?.append(String(ch, start, length))
            }
        })
        return ScriptDocument(title, "ODT", paragraphs)
    }
}
