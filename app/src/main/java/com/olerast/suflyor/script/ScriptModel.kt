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
    /** Language the tokens were normalized for: the tracker and the recognizer must use the same one. */
    val lang: SpeechLang = SpeechLang.RU,
) {
    val spokenTokens: Int = tokens.count { it.spoken }

    /** Reading time at a given pace, words per minute. */
    fun durationSeconds(wpm: Int): Int = if (wpm <= 0) 0 else (spokenTokens * 60 + wpm - 1) / wpm

    companion object {
        val EMPTY = ScriptModel("", "", emptyList(), emptyList(), emptyList())
    }
}

/**
 * Script words to bias the recognizer towards: informative spoken words, as the recognizer of [ScriptModel.lang]
 * would print them. Russian takes the written word, as before English existed; English takes the normalized form, so
 * apostrophes are straight and "Mr." is "mister". The recognizer keeps only what its vocabulary can encode.
 */
fun ScriptModel.biasWords(): List<String> = tokens.asSequence()
    .filter { it.spoken && it.weight >= 1f }
    .map {
        when (lang) {
            SpeechLang.RU -> displayText.substring(it.start, it.end).lowercase()
            SpeechLang.EN -> it.norm
        }
    }
    .distinct()
    .take(2000)
    .toList()

object ScriptLayout {
    const val PARAGRAPH_SEPARATOR = "\n\n"

    /**
     * @param phraseMode one phrase per line (break at sentence ends, clause punctuation and long phrases);
     *   otherwise paragraphs are left to wrap at the window width.
     * @param lang language the reader speaks: how words are split, normalized and weighted, and where lines break.
     */
    fun build(doc: ScriptDocument, phraseMode: Boolean, maxWords: Int = 7, lang: SpeechLang = SpeechLang.RU): ScriptModel {
        val sb = StringBuilder()
        val emphasis = mutableListOf<IntRange>()
        val notes = mutableListOf<IntRange>()
        for (p in doc.paragraphs) {
            if (p.text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append(PARAGRAPH_SEPARATOR)
            val offset = sb.length
            val chars = p.text.toCharArray()
            if (phraseMode && p.kind == Paragraph.Kind.BODY) {
                for (pos in PhraseBreaker.breakPositions(p.text, maxWords, lang)) chars[pos] = '\n'
            }
            sb.append(chars)
            p.emphasis.forEach { emphasis += (it.first + offset)..(it.last + offset) }
            if (p.kind == Paragraph.Kind.HEADING) notes += offset until offset + p.text.length
        }
        val text = sb.toString()
        notes += bracketNotes(text)
        val noteChars = BooleanArray(text.length)
        for (r in notes) for (k in r) if (k in noteChars.indices) noteChars[k] = true
        val tokens = lang.wordRegex.findAll(text).mapNotNull { m ->
            val norm = lang.normalize(m.value)
            if (norm.isEmpty()) return@mapNotNull null
            val inNote = noteChars[m.range.first]
            Token(
                norm, m.range.first, m.range.last + 1,
                spoken = !inNote,
                weight = lang.weight(norm, lang.isForeign(m.value)),
                anchor = startsLine(text, m.range.first, lang),
            )
        }.toList()
        return ScriptModel(doc.title, text, tokens, emphasis, notes.sortedBy { it.first }, lang)
    }

    /**
     * A word starts a line if only spaces separate it from a line break, sentence end or stage direction.
     * In English, curly quotes are skipped too (“Stop.” ‘Why?’ he asked), and the period of "Mr." or "e.g." is not
     * a sentence end.
     */
    private fun startsLine(text: String, offset: Int, lang: SpeechLang): Boolean {
        val en = lang == SpeechLang.EN
        var i = offset - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '"' || text[i] == '«' || text[i] == '(' || text[i] == '—' || text[i] == '–' ||
                (en && text[i] in "“”‘’"))
        ) i--
        if (i < 0) return true
        if (text[i] !in "\n.!?…:;]") return false
        return !(en && text[i] == '.' && EnglishNorm.isAbbreviation(wordEndingAt(text, i)))
    }

    /** The word (up to whitespace) whose last character is at [last]: "Mr." for the period of "Mr.". */
    private fun wordEndingAt(text: String, last: Int): String {
        var s = last
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        return text.substring(s, last + 1)
    }

    /** [Stage directions in square brackets] are displayed but skipped by the voice tracker. */
    private fun bracketNotes(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = text.indexOf('[')
        while (i >= 0) {
            val close = text.indexOf(']', i + 1)
            if (close < 0) break
            val paraEnd = text.indexOf(PARAGRAPH_SEPARATOR, i).let { if (it < 0) text.length else it }
            val reopen = text.indexOf('[', i + 1)
            // An unmatched "[" (closed only in a later paragraph, or reopened first) is plain text: skip just it.
            if (close > paraEnd || (reopen in 0 until close)) {
                i = reopen
                continue
            }
            out += i..close
            i = text.indexOf('[', close + 1)
        }
        return out
    }
}

/** Splits a paragraph into phrases that can be said in one breath; returns positions of spaces to turn into line breaks. */
object PhraseBreaker {
    /** Words a phrase may start with. The English entries here predate [CONJUNCTIONS_EN] and stay for Russian scripts. */
    private val CONJUNCTIONS = setOf(
        "и", "а", "но", "или", "что", "чтобы", "потому", "поэтому", "если", "когда", "который", "которая",
        "которое", "которые", "которых", "где", "как", "чем", "хотя", "пока", "ведь", "либо", "зато", "однако",
        "then", "and", "but", "because", "which", "that", "when", "if",
    )
    private val CONJUNCTIONS_EN = setOf(
        "and", "but", "or", "so", "because", "which", "that", "when", "if", "while", "although", "though", "where", "who",
        "unless", "until", "then",
    )
    private val SENTENCE_END = Regex("[.!?…]+[\"'»”’)\\]]*$")
    private val CLAUSE_END = Regex("[,;:]+[\"'»”’)\\]]*$")
    private val DASHES = setOf("—", "–", "-")

    fun breakPositions(text: String, maxWords: Int, lang: SpeechLang = SpeechLang.RU): List<Int> {
        val en = lang == SpeechLang.EN
        val conjunctions = if (en) CONJUNCTIONS_EN else CONJUNCTIONS

        // "Mr." and "e.g." end with a period, not a sentence. Russian keeps its old rule.
        fun endsSentence(w: String) = SENTENCE_END.containsMatchIn(w) && !(en && EnglishNorm.isAbbreviation(w))

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
                endsSentence(w) -> true
                (CLAUSE_END.containsMatchIn(w) || w in DASHES) && count >= 3 -> true
                count >= maxWords -> true
                count >= maxWords - 2 && next in conjunctions -> true
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
            if (k == phraseStart && phraseStart > 0 && !endsSentence(words[phraseStart - 1])) {
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
