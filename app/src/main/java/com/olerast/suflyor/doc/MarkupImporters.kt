package com.olerast.suflyor.doc

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Minimal RTF reader: paragraphs, \b bold, \'hh bytes in the document code page, \uN escapes; skips tables of fonts, pictures etc. */
object RtfImporter {
    private val SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "headerf",
        "footerl", "footerr", "footerf", "object", "fldinst", "themedata", "colorschememapping", "latentstyles",
        "datastore", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl", "generator", "filetbl", "revtbl",
        "footnote", "annotation", "bkmkstart", "bkmkend", "shppict", "nonshppict", "blipuid",
    )

    private class State(var skip: Boolean, var bold: Boolean, var uc: Int, var font: Int)

    /** RTF \fcharset numbers → code pages (Cyrillic WordPad text is \fcharset204 even with \ansicpg1252). */
    private val FONT_CHARSETS = mapOf(
        128 to "Shift_JIS", 129 to "MS949", 134 to "GBK", 136 to "Big5", 161 to "windows-1253", 162 to "windows-1254",
        163 to "windows-1258", 177 to "windows-1255", 178 to "windows-1256", 186 to "windows-1257", 204 to "windows-1251",
        222 to "windows-874", 238 to "windows-1250",
    )
    private val FONT_ENTRY = Regex("\\\\f(\\d{1,9})[^;{}]{0,200}?\\\\fcharset(\\d{1,9})")

    fun parse(bytes: ByteArray, title: String): ScriptDocument {
        val src = String(bytes, Charsets.ISO_8859_1)
        if (!src.startsWith("{\\rtf")) throw ImportException("Это не RTF-файл")
        var charset: Charset = Charset.forName("windows-1252")
        // The font table sits at the start; map each font to the code page of its \fcharset.
        val fontTable = src.indexOf("\\fonttbl").let { if (it < 0) "" else src.substring(it, minOf(src.length, it + 64 * 1024)) }
        val fontCharsets = HashMap<Int, Charset>()
        for (m in FONT_ENTRY.findAll(fontTable)) {
            val name = FONT_CHARSETS[m.groupValues[2].toInt()] ?: continue
            runCatching { Charset.forName(name) }.onSuccess { fontCharsets[m.groupValues[1].toInt()] = it }
        }
        val paragraphs = mutableListOf<Paragraph>()
        val pb = ParagraphBuilder()
        val pendingBytes = ByteArrayOutputStream()
        val stack = ArrayDeque<State>()
        var state = State(skip = false, bold = false, uc = 1, font = -1)
        var skipChars = 0
        var groupStart = false

        fun flushBytes() {
            if (pendingBytes.size() > 0) {
                val cs = fontCharsets[state.font] ?: charset
                if (!state.skip) pb.append(String(pendingBytes.toByteArray(), cs), state.bold)
                pendingBytes.reset()
            }
        }

        fun emitText(text: String) {
            flushBytes()
            if (!state.skip) pb.append(text, state.bold)
        }

        fun endParagraph() {
            flushBytes()
            pb.build()?.let { paragraphs += it }
            pb.clear()
        }

        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '{' -> {
                    flushBytes()
                    stack.addLast(state)
                    state = State(state.skip, state.bold, state.uc, state.font)
                    groupStart = true
                    i++
                    continue
                }
                c == '}' -> {
                    flushBytes()
                    state = stack.removeLastOrNull() ?: state
                    i++
                }
                c == '\\' && i + 1 < src.length -> {
                    val next = src[i + 1]
                    when {
                        next == '\'' && i + 3 < src.length -> {
                            val b = src.substring(i + 2, i + 4).toIntOrNull(16)
                            i += 4
                            if (skipChars > 0) {
                                skipChars--
                            } else if (b != null && !state.skip) {
                                pendingBytes.write(b)
                            }
                        }
                        next == '*' -> {
                            state.skip = true
                            i += 2
                        }
                        next == '\\' || next == '{' || next == '}' -> {
                            emitText(next.toString())
                            i += 2
                        }
                        next == '~' -> {
                            emitText(" ")
                            i += 2
                        }
                        next == '-' || next == '_' -> {
                            if (next == '_') emitText("-")
                            i += 2
                        }
                        next == '\n' || next == '\r' -> {
                            endParagraph()
                            i += 2
                        }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < src.length && src[j].isLetter()) j++
                            val word = src.substring(i + 1, j)
                            var k = j
                            if (k < src.length && (src[k] == '-' || src[k].isDigit())) {
                                k++
                                while (k < src.length && src[k].isDigit()) k++
                            }
                            val param = src.substring(j, k).toIntOrNull()
                            if (k < src.length && src[k] == ' ') k++
                            i = k
                            if (groupStart && word in SKIP_DESTINATIONS) state.skip = true
                            when (word) {
                                "par", "line", "sect", "page", "row" -> endParagraph()
                                "cell", "tab", "emspace", "enspace" -> emitText(" ")
                                "b" -> {
                                    flushBytes()
                                    state.bold = param != 0
                                }
                                "plain" -> {
                                    flushBytes()
                                    state.bold = false
                                }
                                "f" -> if (param != null && !state.skip) {
                                    flushBytes()
                                    state.font = param
                                }
                                "deff" -> if (param != null && state.font < 0) state.font = param
                                "uc" -> state.uc = param ?: 1
                                "u" -> if (param != null) {
                                    val code = if (param < 0) param + 65536 else param
                                    emitText(code.toChar().toString())
                                    skipChars = state.uc
                                }
                                "ansicpg" -> if (param != null) {
                                    charset = runCatching { Charset.forName("windows-$param") }.getOrDefault(charset)
                                }
                                "emdash" -> emitText("—")
                                "endash" -> emitText("–")
                                "lquote", "rquote" -> emitText("'")
                                "ldblquote", "rdblquote" -> emitText("\"")
                                "bullet" -> emitText("•")
                            }
                        }
                        else -> i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> {
                    if (skipChars > 0) {
                        skipChars--
                    } else if (!state.skip) {
                        pendingBytes.write(c.code and 0xFF)
                    }
                    i++
                }
            }
            groupStart = false
        }
        endParagraph()
        return ScriptDocument(title, "RTF", paragraphs)
    }
}

/** HTML / saved web pages / Google Docs HTML export: block tags become paragraphs, <b>/<strong> and bold spans become emphasis. */
object HtmlImporter {
    private val REMOVED_BLOCKS = listOf("script", "style", "head", "noscript", "template")

    /** The attribute run stops at the next '<', so an unclosed tag can't make the scan quadratic. */
    private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]{0,30})([^<>]*)>")
    private val BLOCK = setOf(
        "p", "div", "br", "li", "tr", "section", "article", "blockquote", "ul", "ol", "table", "hr",
        "header", "footer", "main", "aside", "nav", "dd", "dt", "pre", "figure", "figcaption",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BOLD_STYLE = Regex("font-weight\\s*:\\s*(bold|[6-9]00)", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…", "shy" to "­",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "bull" to "•",
        "copy" to "©", "reg" to "®", "trade" to "™", "times" to "×", "minus" to "−", "deg" to "°",
    )

    /**
     * Drops comments, declarations (<!DOCTYPE>, <?xml?>, Word's <![if …]>) and script/style/head blocks in one
     * linear pass.
     */
    private fun stripBlocks(html: String): String {
        val out = StringBuilder(html.length)
        // Searches start further and further right, so a remembered result stays valid until we pass it:
        // a thousand unclosed "<script" or "<!" then cost one scan of the rest, not a thousand.
        val memo = HashMap<String, IntArray>()
        fun find(needle: String, from: Int): Int {
            val m = memo.getOrPut(needle) { intArrayOf(-2, -2) }
            if (m[0] != -2 && from >= m[0] && (m[1] == -1 || m[1] >= from)) return m[1]
            val r = html.indexOf(needle, from, ignoreCase = true)
            m[0] = from
            m[1] = r
            return r
        }
        fun afterTag(from: Int) = find(">", from).let { if (it < 0) html.length else it + 1 }
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                out.append(html, i, html.length)
                break
            }
            out.append(html, i, lt)
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                i = if (end < 0) html.length else end + 3
                out.append(' ')
                continue
            }
            if (html.startsWith("<!", lt) || html.startsWith("<?", lt)) {
                i = afterTag(lt)
                out.append(' ')
                continue
            }
            val block = REMOVED_BLOCKS.firstOrNull { tag ->
                html.startsWith("<$tag", lt, ignoreCase = true) && lt + tag.length + 1 < html.length &&
                    !html[lt + tag.length + 1].isLetterOrDigit()
            }
            if (block != null) {
                val end = find("</$block", lt + 1)
                // </head> is optional in HTML5: without it, the body starts the content.
                if (block == "head" && end < 0) {
                    val body = find("<body", lt + 1)
                    if (body >= 0) {
                        i = body
                        out.append(' ')
                        continue
                    }
                }
                // Without a closing tag only the opening tag goes; the rest of the document stays.
                i = if (end < 0) afterTag(lt) else afterTag(end)
                out.append(' ')
                continue
            }
            out.append('<')
            i = lt + 1
        }
        return out.toString()
    }

    fun parse(html: String, title: String): ScriptDocument {
        val src = stripBlocks(html)
        val paragraphs = mutableListOf<Paragraph>()
        val pb = ParagraphBuilder()
        var kind = Paragraph.Kind.BODY
        var boldDepth = 0
        val spanBold = ArrayDeque<Boolean>()

        fun flush() {
            pb.build(kind)?.let { paragraphs += it }
            pb.clear()
            kind = Paragraph.Kind.BODY
        }

        var last = 0
        for (m in TAG.findAll(src)) {
            if (m.range.first > last) pb.append(decode(src.substring(last, m.range.first)), boldDepth > 0)
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            when {
                name in BLOCK -> {
                    flush()
                    if (!closing && name in HEADINGS) kind = Paragraph.Kind.HEADING
                }
                name == "b" || name == "strong" -> boldDepth = (boldDepth + if (closing) -1 else 1).coerceAtLeast(0)
                name == "span" -> if (closing) {
                    if (spanBold.removeLastOrNull() == true) boldDepth = (boldDepth - 1).coerceAtLeast(0)
                } else {
                    val bold = BOLD_STYLE.containsMatchIn(attrs)
                    spanBold.addLast(bold)
                    if (bold) boldDepth++
                }
            }
        }
        if (last < src.length) pb.append(decode(src.substring(last)), boldDepth > 0)
        flush()
        val title2 = Regex("(?is)<title[^<>]{0,200}>([^<]{0,300})</title>").find(html.take(64 * 1024))?.groupValues?.get(1)?.trim()
        return ScriptDocument(title2?.takeIf { it.isNotEmpty() }?.let(::decode) ?: title, "HTML", paragraphs)
    }

    fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String = runCatching { String(Character.toChars(cp)) }.getOrDefault("")
}
