package com.olerast.suflyor.script

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument

/** One word of the displayed script. [start]/[end] are offsets in [ScriptModel.displayText], end exclusive. */
data class Token(
    val norm: String,
    val start: Int,
    val end: Int,
    /** False for headings and [stage directions]: shown, but not expected to be spoken. */
    val spoken: Boolean,
    val weight: Float,
    /** First word of a displayed line, sentence or paragraph: where readers restart or jump to. */
    val anchor: Boolean = false,
)

class ScriptModel(
    val title: String,
    val displayText: String,
    val tokens: List<Token>,
    val emphasis: List<IntRange>,
    val notes: List<IntRange>,
) {
    val spokenTokens: Int = tokens.count { it.spoken }

    /** Reading time at a given pace, words per minute. */
    fun durationSeconds(wpm: Int): Int = if (wpm <= 0) 0 else (spokenTokens * 60 + wpm - 1) / wpm

    companion object {
        val EMPTY = ScriptModel("", "", emptyList(), emptyList(), emptyList())
    }
}

object ScriptLayout {
    const val PARAGRAPH_SEPARATOR = "\n\n"

    /**
     * @param phraseMode one phrase per line (break at sentence ends, clause punctuation and long phrases);
     *   otherwise paragraphs are left to wrap at the window width.
     */
    fun build(doc: ScriptDocument, phraseMode: Boolean, maxWords: Int = 7): ScriptModel {
        val sb = StringBuilder()
        val emphasis = mutableListOf<IntRange>()
        val notes = mutableListOf<IntRange>()
        for (p in doc.paragraphs) {
            if (p.text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append(PARAGRAPH_SEPARATOR)
            val offset = sb.length
            val chars = p.text.toCharArray()
            if (phraseMode && p.kind == Paragraph.Kind.BODY) {
                for (pos in PhraseBreaker.breakPositions(p.text, maxWords)) chars[pos] = '\n'
            }
            sb.append(chars)
            p.emphasis.forEach { emphasis += (it.first + offset)..(it.last + offset) }
            if (p.kind == Paragraph.Kind.HEADING) notes += offset until offset + p.text.length
        }
        val text = sb.toString()
        notes += bracketNotes(text)
        val tokens = TextNorm.WORD.findAll(text).mapNotNull { m ->
            val norm = TextNorm.normalizeWord(m.value)
            if (norm.isEmpty()) return@mapNotNull null
            val inNote = notes.any { m.range.first in it }
            val wasLatin = m.value.any { it in 'a'..'z' || it in 'A'..'Z' }
            Token(
                norm, m.range.first, m.range.last + 1,
                spoken = !inNote,
                weight = TextNorm.weight(norm, wasLatin),
                anchor = startsLine(text, m.range.first),
            )
        }.toList()
        return ScriptModel(doc.title, text, tokens, emphasis, notes.sortedBy { it.first })
    }

    /** A word starts a line if only spaces separate it from a line break, sentence end or stage direction. */
    private fun startsLine(text: String, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '"' || text[i] == '«' || text[i] == '(' || text[i] == '—' || text[i] == '–')) i--
        return i < 0 || text[i] in "\n.!?…:;]"
    }

    /** [Stage directions in square brackets] are displayed but skipped by the voice tracker. */
    private fun bracketNotes(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = text.indexOf('[')
        while (i >= 0) {
            val close = text.indexOf(']', i + 1)
            val paraEnd = text.indexOf(PARAGRAPH_SEPARATOR, i).let { if (it < 0) text.length else it }
            if (close < 0 || close > paraEnd) break
            out += i..close
            i = text.indexOf('[', close + 1)
        }
        return out
    }
}

/** Splits a paragraph into phrases that can be said in one breath; returns positions of spaces to turn into line breaks. */
object PhraseBreaker {
    private val CONJUNCTIONS = setOf(
        "и", "а", "но", "или", "что", "чтобы", "потому", "поэтому", "если", "когда", "который", "которая",
        "которое", "которые", "которых", "где", "как", "чем", "хотя", "пока", "ведь", "либо", "зато", "однако",
        "then", "and", "but", "because", "which", "that", "when", "if",
    )
    private val SENTENCE_END = Regex("[.!?…]+[\"'»”’)\\]]*$")
    private val CLAUSE_END = Regex("[,;:]+[\"'»”’)\\]]*$")
    private val DASHES = setOf("—", "–", "-")

    fun breakPositions(text: String, maxWords: Int): List<Int> {
        val starts = mutableListOf<Int>()
        val words = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i] == ' ') i++
            if (i >= text.length) break
            val s = i
            while (i < text.length && text[i] != ' ') i++
            starts += s
            words += text.substring(s, i)
        }
        if (words.size <= 1) return emptyList()

        // breakAfter[k] == true -> line break after word k.
        val breakAfter = BooleanArray(words.size)
        var count = 0
        for (k in 0 until words.size - 1) {
            count++
            val w = words[k]
            val next = words[k + 1].lowercase().trim { !it.isLetterOrDigit() }
            val cut = when {
                SENTENCE_END.containsMatchIn(w) -> true
                (CLAUSE_END.containsMatchIn(w) || w in DASHES) && count >= 3 -> true
                count >= maxWords -> true
                count >= maxWords - 2 && next in CONJUNCTIONS -> true
                else -> false
            }
            if (cut) {
                breakAfter[k] = true
                count = 0
            }
        }
        // Do not leave a lonely word on its own line unless it is a whole sentence.
        var phraseStart = 0
        for (k in words.indices) {
            val endsPhrase = k == words.size - 1 || breakAfter[k]
            if (!endsPhrase) continue
            if (k == phraseStart && phraseStart > 0 && !SENTENCE_END.containsMatchIn(words[phraseStart - 1])) {
                breakAfter[phraseStart - 1] = false
            }
            phraseStart = k + 1
        }
        val positions = mutableListOf<Int>()
        for (k in 0 until words.size - 1) {
            if (breakAfter[k]) positions += starts[k + 1] - 1
        }
        return positions.filter { it >= 0 && text[it] == ' ' }
    }
}
