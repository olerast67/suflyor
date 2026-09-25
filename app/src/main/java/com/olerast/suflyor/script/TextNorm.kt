package com.olerast.suflyor.script

import java.text.Normalizer

/**
 * Word normalization and fuzzy comparison tuned for Russian speech recognition output. English speech uses
 * [EnglishNorm]; [SpeechLang] picks one. Everything here is Russian behaviour and must not change for English's sake.
 */
object TextNorm {
    /** Letters, digits and combining marks (stress marks like "замо́к" stay inside the word). */
    val WORD = Regex("[\\p{L}\\p{N}\\p{M}]+")

    /**
     * Function words. The Latin entries at the end are never reached: [normalizeWord] transliterates Latin before the
     * lookup ("the" becomes "те"). They stay so Russian weights stay exactly as they are; English stop words live in
     * [EnglishNorm.STOP].
     */
    private val STOP = setOf(
        "и", "в", "во", "не", "на", "я", "что", "с", "со", "а", "это", "как", "то", "по", "но", "к", "ко", "у",
        "из", "за", "о", "об", "же", "так", "вы", "мы", "он", "она", "оно", "они", "ты", "его", "ее", "их", "им",
        "ей", "мне", "меня", "мой", "моя", "мое", "мои", "бы", "ли", "да", "нет", "от", "до", "для", "при", "про",
        "без", "над", "под", "все", "вот", "там", "тут", "уже", "еще", "или", "если", "чем", "тоже", "ну", "есть",
        "был", "была", "было", "были", "быть", "этот", "эта", "эти", "тот", "та", "те", "вас", "нас", "вам", "нам",
        "the", "a", "an", "and", "of", "to", "in", "is", "it", "on", "for", "at", "by",
    )

    /** Hesitations that speech recognizers emit and that never appear in a script. */
    val FILLERS = setOf("э", "ээ", "эээ", "эм", "эмм", "мм", "ммм", "хм", "ага", "угу")

    fun normalizeWord(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in Normalizer.normalize(raw, Normalizer.Form.NFC)) {
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()
            ) continue
            val c = ch.lowercaseChar()
            sb.append(if (c == 'ё') 'е' else c)
        }
        val s = sb.toString()
        return if (s.any { it in 'a'..'z' }) translit(s) else s
    }

    fun words(text: String): List<String> =
        WORD.findAll(text).map { normalizeWord(it.value) }.filter { it.isNotEmpty() }.toList()

    fun isNumber(norm: String): Boolean = norm.isNotEmpty() && norm.all { it.isDigit() }

    /** How much a matched word proves the position: function words prove little, long words a lot, numbers nothing. */
    fun weight(norm: String, wasLatin: Boolean = false): Float = when {
        isNumber(norm) -> 0f
        norm in STOP -> 0.2f
        norm.length <= 2 -> 0.3f
        norm.length == 3 -> 0.6f
        wasLatin -> 0.5f
        else -> 1f
    }

    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val minLen = minOf(a.length, b.length)
        val maxLen = maxOf(a.length, b.length)
        if (maxLen <= 2) return 0f
        var p = 0
        while (p < minLen && a[p] == b[p]) p++
        // Russian endings: "сделал"/"сделали", "дома"/"домой".
        if (p >= 5) return 0.92f
        if (minLen >= 4 && p >= 4 && p >= minLen - 2) return 0.88f
        if (minLen >= 4 && p >= 3 && p >= minLen - 1) return 0.8f
        return 1f - levenshtein(a, b).toFloat() / maxLen
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    private val DIGRAPHS = listOf(
        "tion" to "шн", "sch" to "ш", "igh" to "ай", "you" to "ю", "sh" to "ш", "ch" to "ч", "th" to "т",
        "ph" to "ф", "ck" to "к", "qu" to "кв", "kh" to "х", "zh" to "ж", "ts" to "ц", "oo" to "у", "ee" to "и",
        "ea" to "и", "ou" to "ау", "ay" to "ей", "ey" to "ей", "ai" to "ей", "oa" to "о", "ew" to "ью", "ow" to "оу",
        "yo" to "йо", "yu" to "ю", "ya" to "я", "wh" to "в", "gh" to "г",
    )
    private val SINGLE = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "х", 'i' to "и",
        'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р",
        's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "и", 'z' to "з",
    )

    /**
     * Rough Latin-to-Cyrillic reading so "YouTube" can match what a Russian recognizer hears ("ютуб").
     * Only for Latin words in Russian scripts; an English script is normalized by [EnglishNorm] without it.
     */
    fun translit(latin: String): String {
        var s = latin
        if (s.length > 3 && s.endsWith("e") && s[s.length - 2] !in "aeiouy") s = s.dropLast(1)
        val sb = StringBuilder()
        var i = 0
        outer@ while (i < s.length) {
            for ((from, to) in DIGRAPHS) {
                if (s.startsWith(from, i)) {
                    sb.append(to)
                    i += from.length
                    continue@outer
                }
            }
            val ch = s[i]
            sb.append(SINGLE[ch] ?: ch.toString())
            i++
        }
        return sb.toString()
    }
}
