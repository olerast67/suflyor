package com.olerast.suflyor.script

import java.text.Normalizer

/**
 * Word normalization and fuzzy comparison for English speech. The English recognizer prints UPPER CASE words with
 * straight apostrophes ("DON'T"); scripts have any case and curly apostrophes. [SpeechLang] picks this or [TextNorm].
 */
object EnglishNorm {
    /** Inner apostrophes stay inside the word (don't, it’s); hyphens still split ("carry-on": the recognizer says CARRY ON). */
    val WORD = Regex("[\\p{L}\\p{N}\\p{M}]+(?:['’ʼ‘`][\\p{L}\\p{N}\\p{M}]+)*")

    private const val APOSTROPHES = "’ʼ‘`"

    /** Function words: they prove little about the position (weight 0.2) and may not stand for a longer word's start. */
    val STOP: Set<String> = """
        a an the and or but nor so yet of to in on at by for from with into onto about as than then
        is are was were be been being am do does did done have has had having will would shall should can could may might must
        i me my mine you your yours he him his she her hers it its we us our ours they them their theirs
        this that these those there here what which who whom whose when where why how not no if just also too very all any
        some such only own same i'm i've i'll i'd you're you've you'll you'd he's she's it's we're we've we'll they're they've
        they'll that's there's here's what's let's isn't aren't wasn't weren't don't doesn't didn't can't won't oh okay well yeah yes
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** Hesitations that speech recognizers emit and that never appear in a script. */
    val FILLERS = setOf("uh", "um", "uhm", "umm", "er", "erm", "ah", "ahh", "hmm", "hm", "mm", "mhm", "eh")

    /** Written short forms as the recognizer says them; applied to script and recognized words alike. */
    private val SPOKEN = mapOf(
        "mr" to "mister", "mrs" to "missus", "dr" to "doctor", "vs" to "versus", "ok" to "okay", "prof" to "professor",
        "jr" to "junior", "sr" to "senior", "approx" to "approximately", "govt" to "government", "km" to "kilometers",
        "kg" to "kilograms", "ft" to "feet", "lbs" to "pounds", "hrs" to "hours",
    )

    /** How a recognizer spells out numbers. Dropped from what was heard when the script writes numbers in digits. */
    val NUMBER_WORDS: Set<String> = """
        zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen
        seventeen eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety hundred thousand million billion trillion
        first second third fourth fifth sixth seventh eighth ninth tenth eleventh twelfth thirteenth fourteenth fifteenth sixteenth
        seventeenth eighteenth nineteenth twentieth thirtieth fortieth fiftieth sixtieth seventieth eightieth ninetieth hundredth
        thousandth millionth point percent dollar dollars cent cents
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** Lowercase, without the final period: "Mr." and "e.g." do not end a sentence. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "a.m", "p.m", "u.s", "u.k", "inc", "ltd",
        "approx", "dept", "fig", "vol",
    )

    fun normalizeWord(raw: String): String {
        val sb = StringBuilder(raw.length)
        // NFD, unlike Russian: "café" and "résumé" lose their accents and match CAFE and RESUME.
        for (ch in Normalizer.normalize(raw, Normalizer.Form.NFD)) {
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()
            ) continue
            val c = ch.lowercaseChar()
            sb.append(if (c in APOSTROPHES) '\'' else c)
        }
        val s = sb.toString()
        // Cyrillic words are not transliterated: they get weight 0 and are never matched.
        return SPOKEN[s] ?: s
    }

    /** How much a matched word proves the position: function words prove little, long words a lot, numbers nothing. */
    fun weight(norm: String, foreign: Boolean): Float {
        val letters = norm.count { it != '\'' }
        return when {
            // Cyrillic in an English script: the English model can't say it, so it is skipped like a number.
            foreign -> 0f
            // 2025, 1st, 21st, 90s, 4k, 10x: said as number words.
            norm.isEmpty() || norm[0].isDigit() -> 0f
            norm in STOP -> 0.2f
            letters <= 2 -> 0.3f
            letters == 3 -> 0.6f
            else -> 1f
        }
    }

    private val SUFFIXES = arrayOf("ing", "ed")

    /**
     * Light suffix stripping that groups word forms (models/model, saved/save, running/run, batteries/battery) for
     * the frequency weights and the similarity; not a lemmatizer.
     */
    fun stem(word: String): String {
        var w = word.replace("'", "")
        w = when {
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 4 && w.endsWith("sses") -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
            else -> w
        }
        for (suffix in SUFFIXES) {
            if (w.endsWith(suffix) && w.length - suffix.length >= 3 && w.dropLast(suffix.length).any { it in "aeiouy" }) {
                w = w.dropLast(suffix.length)
                // "running" -> "runn" -> "run", but "falling" -> "fall".
                if (w.length >= 3 && w[w.length - 1] == w[w.length - 2] && w.last() !in "lsz") w = w.dropLast(1)
                break
            }
        }
        if (w.length > 5 && w.endsWith("ly")) w = w.dropLast(2)
        if (w.length > 3 && (w.last() == 'e' || w.last() == 'y')) w = w.dropLast(1)
        return w
    }

    /**
     * Stricter than the Russian rules: English short words that differ in one letter are different words
     * (the/then, want/went, cold/gold), and a long shared start is not enough (internet/interview).
     */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val minLen = minOf(a.length, b.length)
        val maxLen = maxOf(a.length, b.length)
        if (maxLen <= 2) return 0f
        // model/models, run/running, it's/its, don't/dont. A stem keeps the first letter: other pairs skip the work.
        if (minLen >= 3 && a[0] == b[0] && stem(a) == stem(b)) return 0.9f
        if (maxLen <= 4) return 0f
        var p = 0
        while (p < minLen && a[p] == b[p]) p++
        // understand/understood, but not internet/interview.
        if (p >= 5 && maxLen - p <= 3) return 0.88f
        return 1f - TextNorm.levenshtein(a, b).toFloat() / maxLen
    }

    /**
     * Makes recognized words comparable with the script:
     * - spelled letters ("C E O") and words split in two ("YOU TUBE") are joined when the joined form is a script word;
     * - spoken numbers ("TWENTY TWENTY FIVE") are dropped when the script writes numbers in digits and does not use that
     *   word itself: the digits are transparent to the tracker, the words would be unmatched extra words.
     */
    fun prepareHypothesis(hyp: List<String>, vocab: Set<String>, scriptHasDigits: Boolean): List<String> {
        val out = ArrayList<String>(hyp.size)
        var i = 0
        outer@ while (i < hyp.size) {
            for (k in minOf(4, hyp.size - i) downTo 2) {
                val parts = hyp.subList(i, i + k)
                val raw = parts.joinToString("")
                val joined = SPOKEN[raw] ?: raw
                if (joined in vocab && (parts.all { it.length == 1 } || (k == 2 && joined.length >= 5))) {
                    out += joined
                    i += k
                    continue@outer
                }
            }
            val h = hyp[i++]
            if (scriptHasDigits && (h.firstOrNull()?.isDigit() == true || (h in NUMBER_WORDS && h !in vocab))) continue
            out += h
        }
        return out
    }

    /** "Mr.", "e.g.", "U.S." or an initial ("J."): the period does not end a sentence. */
    fun isAbbreviation(word: String): Boolean {
        val core = word.trimStart { !it.isLetterOrDigit() }.trimEnd { !it.isLetterOrDigit() }.lowercase()
        return core in ABBREVIATIONS || (core.length == 1 && core[0].isLetter() && core != "i" && core != "a")
    }
}
