package com.olerast.suflyor.doc

/**
 * Markdown (including Obsidian flavour) to teleprompter paragraphs. Keeps headings and **bold** / ==highlight==,
 * drops links, images, code markers, comments and ~~struck-through~~ text.
 */
object MarkdownImporter {
    private val HEADING_START = Regex("^\\s{0,3}#{1,6}\\s+")
    private val LIST = Regex("^\\s*([-*+]|\\d{1,3}[.)])\\s+")
    private val TASK = Regex("^\\[[ xX]]\\s+")
    private val FENCE = Regex("^\\s*(```|~~~)")

    // Every run stops at the next opening bracket or delimiter, so hostile input ("[[[[…", "<<<…") stays linear
    // without capping how long a real link, image (data: URIs) or tag may be.
    // Link targets may hold one level of parentheses: https://en.wikipedia.org/wiki/Mercury_(planet).
    private const val TARGET = "\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)"
    private val IMAGE = Regex("!\\[[^\\[\\]]*]$TARGET|!\\[\\[[^\\[\\]]*]]")
    private val LINK = Regex("\\[([^\\[\\]]+)]$TARGET")
    private val WIKI = Regex("\\[\\[([^\\[\\]|#]*)(?:#[^\\[\\]|]*)?(?:\\|([^\\[\\]]+))?]]")
    private val CODE = Regex("`+([^`]*)`+")
    private val AUTOLINK = Regex("<(https?://|mailto:)[^<>\\s]+>")
    private val HTML_TAG = Regex("</?[a-zA-Z][^<>]*>")
    private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!=~|>])")

    /** Linear: an opener without a partner means no later delimiter of its kind exists, so it fails only once. */
    private val STRONG = Regex("(\\*\\*|__|==)(.+?)\\1")

    /** Removes open…close spans in one pass; an unclosed opener is kept as text so nothing silently disappears. */
    private fun stripDelimited(src: String, open: String, close: String): String {
        if (!src.contains(open)) return src
        val out = StringBuilder(src.length)
        var i = 0
        while (true) {
            val start = src.indexOf(open, i)
            if (start < 0) {
                out.append(src, i, src.length)
                return out.toString()
            }
            out.append(src, i, start)
            val end = src.indexOf(close, start + open.length)
            if (end < 0) {
                out.append(src, start, src.length)
                return out.toString()
            }
            i = end + close.length
        }
    }

    fun parse(text: String, title: String): ScriptDocument {
        var src = text.replace("\r\n", "\n").replace('\r', '\n')
        if (src.startsWith("---\n")) {
            val end = src.indexOf("\n---", 3)
            if (end > 0) {
                val after = src.indexOf('\n', end + 4)
                src = if (after < 0) "" else src.substring(after + 1)
            }
        }
        src = stripDelimited(stripDelimited(src, "<!--", "-->"), "%%", "%%")

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
            val heading = HEADING_START.find(line)
            if (heading != null) {
                flush()
                emit(headingText(line.substring(heading.range.last + 1)), Paragraph.Kind.HEADING)
                continue
            }
            if (isHorizontalRule(line)) {
                flush()
                continue
            }
            var content = stripQuote(line)
            val list = LIST.find(content)
            if (list != null && list.range.first == 0) {
                flush()
                content = TASK.replace(content.substring(list.range.last + 1), "")
                emit(content, Paragraph.Kind.BODY)
                continue
            }
            if (content.trimStart().startsWith("|")) {
                if (isTableSeparator(content)) continue
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
        s = stripDelimited(s, "~~", "~~")
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

    /** "# Title ##" → "Title": a closing run of '#' counts only after a space, so "# C#" stays "C#". */
    private fun headingText(rest: String): String {
        val t = rest.trimEnd()
        val hashes = t.length - t.trimEnd('#').length
        return if (hashes > 0 && (hashes == t.length || t[t.length - hashes - 1].isWhitespace())) {
            t.dropLast(hashes).trimEnd()
        } else {
            t
        }
    }

    // Quote and rule markers are scanned by hand: repeated regex groups recurse once per repetition in
    // java.util.regex, and a line of a few thousand "> " would overflow the stack.

    /** "> > text" → "text". */
    private fun stripQuote(line: String): String {
        var i = 0
        while (i < line.length && line[i].isWhitespace()) i++
        if (i >= line.length || line[i] != '>') return line
        while (i < line.length && line[i] == '>') {
            i++
            if (i < line.length && line[i].isWhitespace()) i++
        }
        return line.substring(i)
    }

    /** "---", "* * *", "___": three or more of one marker, spaces allowed, at most three spaces of indent. */
    private fun isHorizontalRule(line: String): Boolean {
        var i = 0
        while (i < line.length && line[i].isWhitespace()) i++
        if (i > 3 || i >= line.length) return false
        val marker = line[i]
        if (marker != '-' && marker != '*' && marker != '_') return false
        var count = 0
        for (k in i until line.length) {
            val c = line[k]
            if (c == marker) count++ else if (!c.isWhitespace()) return false
        }
        return count >= 3
    }

    /** "|---|:--:|" rows of a table. */
    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        return t.contains("--") && t.all { it == '|' || it == ':' || it == '-' || it == ' ' || it == '\t' }
    }

    /**
     * Removes *italic* / _italic_ markers. Pairs them like the lazy regex
     * `(?<![letter digit * _])([*_])(?=\S)(.+?)(?<=\S)\1(?![letter digit * _])` did on Android, but in one pass:
     * for each opener, the nearest closer of the same kind at least two characters later.
     */
    private fun stripItalic(s: String): String {
        if (s.indexOf('*') < 0 && s.indexOf('_') < 0) return s
        // Android's regex engine (ICU) counts \s as tab, line breaks, form feed and every Unicode space (NBSP too).
        fun blank(c: Char) = c == '\t' || c == '\n' || c == '\r' || c == '\u000C' || Character.isSpaceChar(c)
        fun joins(c: Char) = c.isLetterOrDigit() || c == '*' || c == '_'
        val starClosers = ArrayList<Int>()
        val underClosers = ArrayList<Int>()
        for (j in 1 until s.length) {
            val c = s[j]
            if ((c == '*' || c == '_') && !blank(s[j - 1]) && (j + 1 == s.length || !joins(s[j + 1]))) {
                (if (c == '*') starClosers else underClosers) += j
            }
        }
        val out = StringBuilder(s.length)
        var copied = 0
        var nextStar = 0
        var nextUnder = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if ((c == '*' || c == '_') && (i == 0 || !joins(s[i - 1])) && i + 1 < s.length && !blank(s[i + 1])) {
                val closers = if (c == '*') starClosers else underClosers
                var k = if (c == '*') nextStar else nextUnder
                while (k < closers.size && closers[k] < i + 2) k++
                if (c == '*') nextStar = k else nextUnder = k
                if (k < closers.size) {
                    val close = closers[k]
                    out.append(s, copied, i).append(s, i + 1, close)
                    copied = close + 1
                    i = close + 1
                    continue
                }
            }
            i++
        }
        out.append(s, copied, s.length)
        return out.toString()
    }

    private fun restore(s: String, escapes: List<String>): String =
        if (escapes.isEmpty()) s else Regex("(\\d+)").replace(s) { m ->
            escapes.getOrElse(m.groupValues[1].toInt()) { "" }
        }
}
