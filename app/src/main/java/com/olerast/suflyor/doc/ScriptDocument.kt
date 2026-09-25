package com.olerast.suflyor.doc

import java.text.Normalizer

/** Result of importing any file: plain paragraphs with light formatting, independent of the source format. */
data class ScriptDocument(
    val title: String,
    val format: String,
    val paragraphs: List<Paragraph>,
    val warnings: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = paragraphs.all { it.text.isBlank() }

    companion object {
        /** [format] codes of scripts that don't come from a file; shown translated (ui/Messages.kt). */
        const val FORMAT_TEXT = "TEXT"
        const val FORMAT_SAMPLE = "SAMPLE"
    }
}

data class Paragraph(
    val text: String,
    val kind: Kind = Kind.BODY,
    /** Character ranges of [text] marked as emphasis (bold in docx/md/rtf, ==highlight== in Obsidian). */
    val emphasis: List<IntRange> = emptyList(),
) {
    enum class Kind { HEADING, BODY }
}

class ImportException(message: String) : Exception(message)

/**
 * Accumulates text runs of one paragraph, collapsing whitespace and removing invisible characters,
 * and remembers which characters were emphasized.
 */
class ParagraphBuilder {
    private val sb = StringBuilder()
    private val em = ArrayList<Boolean>()

    fun append(text: CharSequence, emphasized: Boolean = false) {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        for (ch in normalized) {
            when {
                ch == '­' || ch == '​' || ch == '‌' || ch == '‍' ||
                    ch == '⁠' || ch == '﻿' -> Unit
                ch.isWhitespace() || ch == ' ' || ch == ' ' || ch == ' ' -> {
                    if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
                        sb.append(' ')
                        em.add(emphasized)
                    }
                }
                ch < ' ' -> Unit
                else -> {
                    sb.append(ch)
                    em.add(emphasized)
                }
            }
        }
    }

    fun isBlank(): Boolean = sb.isBlank()

    fun clear() {
        sb.setLength(0)
        em.clear()
    }

    fun build(kind: Paragraph.Kind = Paragraph.Kind.BODY): Paragraph? {
        var s = 0
        var e = sb.length
        while (s < e && sb[s] == ' ') s++
        while (e > s && sb[e - 1] == ' ') e--
        if (s >= e) return null
        val ranges = mutableListOf<IntRange>()
        var runStart = -1
        var runEnd = -1
        for (k in s until e) {
            if (sb[k] == ' ') continue
            if (em[k]) {
                if (runStart < 0) runStart = k
                runEnd = k
            } else if (runStart >= 0) {
                ranges += (runStart - s)..(runEnd - s)
                runStart = -1
            }
        }
        if (runStart >= 0) ranges += (runStart - s)..(runEnd - s)
        return Paragraph(sb.substring(s, e), kind, ranges)
    }
}
