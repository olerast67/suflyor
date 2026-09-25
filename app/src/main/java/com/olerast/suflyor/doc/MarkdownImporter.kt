package com.olerast.suflyor.doc

/**
 * Markdown (including Obsidian flavour) to teleprompter paragraphs. Keeps headings and **bold** / ==highlight==,
 * drops links, images, code markers, comments and ~~struck-through~~ text.
 */
object MarkdownImporter {
    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val HR = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val LIST = Regex("^\\s*([-*+]|\\d{1,3}[.)])\\s+")
    private val TASK = Regex("^\\[[ xX]]\\s+")
    private val QUOTE = Regex("^\\s*(>\\s?)+")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val FENCE = Regex("^\\s*(```|~~~)")

    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)|!\\[\\[[^\\]]*]]")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val WIKI = Regex("\\[\\[([^\\]|#]*)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?]]")
    private val CODE = Regex("`+([^`]*)`+")
    private val STRIKE = Regex("~~.*?~~")
    private val AUTOLINK = Regex("<(https?://|mailto:)[^>]+>")
    private val HTML_TAG = Regex("</?[a-zA-Z][^>]*>")
    private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!=~|>])")
    private val STRONG = Regex("(\\*\\*|__|==)(.+?)\\1")
    private val ITALIC = Regex("(?<![\\p{L}\\p{N}*_])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![\\p{L}\\p{N}*_])")

    fun parse(text: String, title: String): ScriptDocument {
        var src = text.replace("\r\n", "\n").replace('\r', '\n')
        if (src.startsWith("---\n")) {
            val end = src.indexOf("\n---", 3)
            if (end > 0) {
                val after = src.indexOf('\n', end + 4)
                src = if (after < 0) "" else src.substring(after + 1)
            }
        }
        src = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(src, "")
        src = Regex("%%.*?%%", RegexOption.DOT_MATCHES_ALL).replace(src, "")

        val paragraphs = mutableListOf<Paragraph>()
        val pending = StringBuilder()
        var inFence = false

        fun emit(raw: String, kind: Paragraph.Kind) {
            inline(raw, kind)?.let { paragraphs += it }
        }

        fun flush() {
            if (pending.isNotBlank()) emit(pending.toString(), Paragraph.Kind.BODY)
            pending.setLength(0)
        }

        for (rawLine in src.split('\n')) {
            val line = rawLine.trimEnd()
            if (FENCE.containsMatchIn(line)) {
                flush()
                inFence = !inFence
                continue
            }
            if (inFence) {
                if (line.isNotBlank()) paragraphs += listOfNotNull(ParagraphBuilder().apply { append(line) }.build())
                continue
            }
            if (line.isBlank()) {
                flush()
                continue
            }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flush()
                emit(heading.groupValues[2], Paragraph.Kind.HEADING)
                continue
            }
            if (HR.matches(line)) {
                flush()
                continue
            }
            var content = QUOTE.replace(line, "")
            val list = LIST.find(content)
            if (list != null && list.range.first == 0) {
                flush()
                content = TASK.replace(content.substring(list.range.last + 1), "")
                emit(content, Paragraph.Kind.BODY)
                continue
            }
            if (content.trimStart().startsWith("|")) {
                if (TABLE_SEP.matches(content)) continue
                content = content.trim().trim('|').split('|').joinToString(" ") { it.trim() }
            }
            if (pending.isNotEmpty()) pending.append(' ')
            pending.append(content.trim())
        }
        flush()
        return ScriptDocument(title, "Markdown", paragraphs)
    }

    /** Converts inline markdown of one paragraph to text with emphasis ranges. */
    fun inline(raw: String, kind: Paragraph.Kind): Paragraph? {
        var s = raw
        s = IMAGE.replace(s, "")
        s = WIKI.replace(s) { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }
        s = LINK.replace(s) { m -> m.groupValues[1] }
        s = AUTOLINK.replace(s, "")
        s = CODE.replace(s) { m -> m.groupValues[1] }
        s = STRIKE.replace(s, "")
        s = HTML_TAG.replace(s, "")
        // Protect escaped characters from being read as markup, restore them at the end.
        val escapes = mutableListOf<String>()
        s = ESCAPE.replace(s) { m ->
            escapes += m.groupValues[1]
            "${escapes.size - 1}"
        }

        val pb = ParagraphBuilder()
        var last = 0
        for (m in STRONG.findAll(s)) {
            pb.append(restore(stripItalic(s.substring(last, m.range.first)), escapes), false)
            pb.append(restore(stripItalic(m.groupValues[2]), escapes), true)
            last = m.range.last + 1
        }
        pb.append(restore(stripItalic(s.substring(last)), escapes), false)
        return pb.build(kind)
    }

    private fun stripItalic(s: String): String = ITALIC.replace(s) { m -> m.groupValues[2] }

    private fun restore(s: String, escapes: List<String>): String =
        if (escapes.isEmpty()) s else Regex("(\\d+)").replace(s) { m ->
            escapes.getOrElse(m.groupValues[1].toInt()) { "" }
        }
}
