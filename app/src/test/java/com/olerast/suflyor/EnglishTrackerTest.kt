package com.olerast.suflyor

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.EnglishNorm
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.ScriptModel
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.script.TextNorm
import com.olerast.suflyor.script.biasWords
import com.olerast.suflyor.speech.hotwordList
import com.olerast.suflyor.track.ScriptTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English following. The recognizer is simulated the way the LibriSpeech model prints: UPPER CASE words with
 * straight apostrophes, derived from the script's surface text, so case, apostrophes and short forms are exercised.
 */
class EnglishTrackerTest {
    private val en = SpeechLang.EN

    private fun build(lines: List<String>, phraseMode: Boolean = true): ScriptModel =
        ScriptLayout.build(ScriptDocument("t", "test", lines.map { Paragraph(it) }), phraseMode = phraseMode, lang = en)

    private val model = build(TrackerSimulation.EN_SCRIPT)
    private val words = model.tokens.filter { it.spoken }.map { it.norm }

    /** What the recognizer prints for each spoken word of [m], in order. */
    private fun said(m: ScriptModel = model): List<String> =
        m.tokens.filter { it.spoken }.map { TrackerSimulation.asrForm(m.displayText.substring(it.start, it.end)) }

    /** Feeds recognizer words one by one, as a growing hypothesis; returns the position after each. */
    private fun hear(t: ScriptTracker, asr: List<String>, history: MutableList<String>): List<Int> {
        val positions = mutableListOf<Int>()
        for (w in asr) {
            history += en.words(w)
            t.onHypothesis(history.takeLast(16))
            positions += t.position
        }
        return positions
    }

    private fun hear(t: ScriptTracker, asr: String, history: MutableList<String>) = hear(t, asr.split(" "), history)

    private fun tracker(m: ScriptModel = model) = ScriptTracker(m.tokens, m.lang)

    private fun idx(word: String, from: Int = 0, list: List<String> = words): Int {
        val i = list.subList(from, list.size).indexOf(word)
        require(i >= 0) { "no $word" }
        return from + i
    }

    private fun weight(raw: String) = en.weight(en.normalize(raw), en.isForeign(raw))

    @Test
    fun detectsLanguage() {
        fun detect(vararg p: Paragraph) = SpeechLang.detect(ScriptDocument("t", "test", p.toList()))
        assertEquals(SpeechLang.RU, detect(Paragraph("Подписывайтесь на мой YouTube и Instagram, там больше видео.")))
        assertEquals(SpeechLang.EN, detect(Paragraph("Say привет to everyone who is watching this video.")))
        assertEquals(SpeechLang.EN, detect(Paragraph("[Показать товар] Buy it now")))
        assertEquals(SpeechLang.EN, detect(Paragraph("Заголовок сценария", Paragraph.Kind.HEADING), Paragraph("Hello there")))
        assertEquals(SpeechLang.RU, detect())
        assertEquals(SpeechLang.EN, SpeechLang.fromCode("en"))
        assertEquals(null, SpeechLang.fromCode("auto"))
    }

    @Test
    fun normalizesEnglishAsr() {
        assertEquals(listOf("don't"), en.words("DON'T"))
        assertEquals(listOf("don't"), en.words("don’t"))
        assertEquals(listOf("don't"), en.words("Donʼt"))
        assertEquals(listOf("state", "of", "the", "art"), en.words("state-of-the-art"))
        assertEquals(listOf("it's", "4k", "uh", "okay"), en.words("IT'S 4K, uh, OK?"))
        assertEquals(listOf("drivers", "seats"), en.words("drivers’ ‘seats’"))
        assertEquals("mister", en.normalize("Mr"))
        assertEquals("doctor", en.normalize("DR"))
        assertEquals("cafe", en.normalize("café"))
        assertEquals("resume", en.normalize("Résumé"))
        // Russian is unchanged: Latin is transliterated and apostrophes split words.
        assertEquals("ютуб", TextNorm.normalizeWord("YouTube"))
        assertEquals(listOf("дон", "т"), TextNorm.words("DON'T"))
        for (s in listOf("DON'T", "Ёлка, замо́к и YouTube!", "в 2025 году", "")) {
            assertEquals(TextNorm.words(s), SpeechLang.RU.words(s))
        }
    }

    @Test
    fun englishWeights() {
        for (w in listOf("2025", "1st", "90s", "4K", "10x")) assertEquals(w, 0f, weight(w))
        for (w in listOf("the", "don’t", "it's", "You're")) assertEquals(w, 0.2f, weight(w))
        assertEquals(0.3f, weight("AI"))
        assertEquals(0.6f, weight("ten"))
        assertEquals(1f, weight("camera"))
        assertEquals(1f, weight("Mr"))
        assertEquals(0f, weight("привет"))
        val m = build(listOf("Say привет to everyone watching."), phraseMode = false)
        val privet = m.tokens.single { it.norm == "привет" }
        assertTrue(privet.spoken)
        assertEquals(0f, privet.weight)
    }

    @Test
    fun englishStemAndSimilarity() {
        for ((a, b) in listOf(
            "models" to "model", "shoots" to "shoot", "starting" to "start", "saved" to "save", "running" to "run",
            "batteries" to "battery",
        )) {
            assertEquals("$a/$b", EnglishNorm.stem(b), EnglishNorm.stem(a))
        }
        for ((a, b) in listOf("model" to "models", "run" to "running", "make" to "making", "it's" to "its", "don't" to "dont")) {
            assertTrue("$a/$b", en.similarity(a, b) >= ScriptTracker.MATCH_SIM)
        }
        for ((a, b) in listOf(
            "the" to "then", "want" to "went", "internet" to "interview", "communication" to "community", "camera" to "drone",
        )) {
            assertTrue("$a/$b", en.similarity(a, b) < ScriptTracker.MATCH_SIM)
        }
    }

    @Test
    fun followsExactReading() {
        val t = tracker()
        hear(t, said(), mutableListOf())
        assertEquals(words.size, t.position)
    }

    @Test
    fun contractionsCurlyAndExpanded() {
        val m = build(listOf("Don’t buy it yet. It’s not the drone you’re looking for, and we’ll see why."))
        assertEquals(listOf("don't", "buy", "it", "yet", "it's"), m.tokens.take(5).map { it.norm })
        val exact = tracker(m)
        hear(exact, "DON'T BUY IT YET IT'S NOT THE DRONE YOU'RE LOOKING FOR AND WE'LL SEE WHY", mutableListOf())
        assertEquals(m.spokenTokens, exact.position)
        val expanded = tracker(m)
        hear(expanded, "DO NOT BUY IT YET IT IS NOT THE DRONE YOU ARE LOOKING FOR AND WE WILL SEE WHY", mutableListOf())
        assertEquals(m.spokenTokens, expanded.position)
    }

    @Test
    fun toleratesWrongEndingsDroppedWordsAndFillers() {
        val t = tracker()
        val noisy = mutableListOf<String>()
        said().forEachIndexed { i, w ->
            when {
                i % 7 == 3 -> Unit // dropped by the recognizer
                i % 5 == 1 && w.length > 5 -> noisy += if (w.endsWith("S")) w.dropLast(1) else w + "S" // wrong ending
                else -> noisy += w
            }
            if (i % 9 == 4) noisy += "UM"
        }
        hear(t, noisy, mutableListOf())
        assertTrue("position ${t.position} of ${words.size}", t.position >= words.size - 2)
    }

    @Test
    fun staysPutOnSilenceAndOffScriptTalk() {
        val t = tracker()
        val history = mutableListOf<String>()
        hear(t, said().take(10), history)
        val before = t.position
        // "second" is a script word ("The second model").
        val trail = hear(t, "SORRY HOLD ON A SECOND MY PHONE IS RINGING", history)
        assertTrue("moved: $trail", trail.all { it == before })
        t.onHypothesis(history.takeLast(16)) // same hypothesis again = silence
        assertEquals(before, t.position)
    }

    @Test
    fun noJumpsOnScatteredCommonScriptWords() {
        val t = tracker()
        val history = mutableListOf<String>()
        hear(t, said().take(20), history)
        val before = t.position
        val trail = hear(t, "MODEL AND CAMERA IF YOU MODEL THE CAMERA AND MODEL", history)
        assertTrue("jumped: $trail", trail.all { it == before })
    }

    @Test
    fun retakeOfShortClauseStartingWithFunctionWords() {
        // "…film, and only then look at the camera.": the reader re-reads the clause after the comma.
        val t = tracker()
        val history = mutableListOf<String>()
        val clauseStart = idx("only") - 1
        read(t, idx("camera") + 1, history)
        hear(t, said().subList(clauseStart, clauseStart + 5), history)
        assertEquals(clauseStart + 5, t.position)
    }

    @Test
    fun retakeOfShortLineStartingWithFunctionWords() {
        // "you need speed and good tracking." is a line of its own.
        val t = tracker()
        val history = mutableListOf<String>()
        val lineStart = idx("need") - 1
        assertTrue(model.tokens.filter { it.spoken }[lineStart].anchor)
        read(t, idx("tracking") + 1, history)
        hear(t, said().subList(lineStart, lineStart + 5), history)
        assertEquals(lineStart + 5, t.position)
    }

    @Test
    fun returnsToPreviousLineAfterTwoWords() {
        // Reading "first decide what exactly…", the reader goes back to the line above: "The main rule is simple:".
        val t = tracker()
        val history = mutableListOf<String>()
        read(t, idx("exactly"), history)
        val lineStart = idx("main") - 1
        hear(t, "THE MAIN", history)
        assertEquals(lineStart + 2, t.position)
    }

    @Test
    fun retakeFarBack() {
        val t = tracker()
        val history = mutableListOf<String>()
        read(t, idx("register") + 1, history)
        val restart = idx("main") - 1
        val trail = hear(t, said().subList(restart, restart + 5), history)
        assertEquals(restart + 5, t.position)
        assertTrue("trail $trail", trail.all { it >= restart || it == trail.first() })
    }

    @Test
    fun skipsABlockFarAhead() {
        val t = tracker()
        val history = mutableListOf<String>()
        read(t, 12, history)
        val before = t.position
        val target = idx("third") - 1 // "The third model is the most expensive one"
        val trail = hear(t, said().subList(target, target + 6), history)
        assertEquals(target + 6, t.position)
        assertTrue("wandered: $trail", trail.all { it == before || it >= target })
    }

    @Test
    fun partiallySpokenLastWordMovesEarly() {
        val t = tracker()
        val history = mutableListOf<String>()
        val i = idx("stabilization")
        read(t, i, history)
        assertEquals(i, t.position)
        t.onHypothesis((history + en.words("STABILI")).takeLast(16))
        assertEquals(i + 1, t.position)
        history += en.words("STABILIZATION")
        t.onHypothesis(history.takeLast(16))
        assertEquals(i + 1, t.position)
    }

    @Test
    fun partialStopWordDoesNotJump() {
        // Read up to "…and", then the last heard word is a complete "THE": it is not the start of "then" two words on.
        val t = tracker()
        val history = mutableListOf<String>()
        val only = idx("only")
        read(t, only, history)
        assertEquals(only, t.position)
        t.onHypothesis((history + en.words("THE")).takeLast(16))
        assertEquals(only, t.position)
        // An unfinished word that is not a whole function word still counts: "TH" of "THEN".
        t.onHypothesis((history + en.words("ONLY TH")).takeLast(16))
        assertEquals(only + 2, t.position)
    }

    @Test
    fun numbersSpokenAsWords() {
        val m = build(
            listOf("The budget came to 12,345,678 dollars, and the deadline moved to 2027 without any penalties."),
            phraseMode = false,
        )
        val t = tracker(m)
        hear(
            t,
            "THE BUDGET CAME TO TWELVE MILLION THREE HUNDRED FORTY FIVE THOUSAND SIX HUNDRED SEVENTY EIGHT DOLLARS AND " +
                "THE DEADLINE MOVED TO TWENTY TWENTY SEVEN WITHOUT ANY PENALTIES",
            mutableListOf(),
        )
        assertEquals(m.spokenTokens, t.position)
    }

    @Test
    fun retakeOfALineStartingWithAYear() {
        val m = build(
            listOf(
                "In 2025 we launched the first version of the app.",
                "It was slow, buggy and nobody downloaded it.",
                "Then we rewrote the tracker from scratch.",
            ),
        )
        val w = m.tokens.filter { it.spoken }.map { it.norm }
        val t = tracker(m)
        val history = mutableListOf<String>()
        hear(t, "IN TWENTY TWENTY FIVE WE LAUNCHED THE FIRST VERSION OF THE APP IT WAS SLOW BUGGY AND NOBODY", history)
        assertEquals(idx("nobody", list = w) + 1, t.position)
        val trail = hear(t, "IN TWENTY TWENTY FIVE WE LAUNCHED THE FIRST", history)
        assertEquals("trail $trail", idx("first", list = w) + 1, t.position)
    }

    @Test
    fun acronymsAndSplitWords() {
        val m = build(listOf("Our AI startup hired a new CEO in the USA, and now we post on YouTube and TikTok every day."), false)
        val w = m.tokens.filter { it.spoken }.map { it.norm }
        val t = tracker(m)
        val history = mutableListOf<String>()
        hear(t, "OUR A I", history)
        assertTrue("ai: ${t.position}", t.position >= idx("ai", list = w))
        hear(t, "STARTUP HIRED A NEW C E O", history)
        assertTrue("ceo: ${t.position}", t.position >= idx("ceo", list = w))
        hear(t, "IN THE U S A", history)
        assertTrue("usa: ${t.position}", t.position >= idx("usa", list = w))
        hear(t, "AND NOW WE POST ON YOU TUBE", history)
        assertEquals(idx("youtube", list = w) + 1, t.position)
        hear(t, "AND TICK TOCK EVERY DAY", history)
        assertEquals(m.spokenTokens, t.position)
    }

    @Test
    fun abbreviationsDoNotBreakLines() {
        val m = build(listOf("Mr. Smith met Dr. Jones at the studio. They talked about the new camera, e.g. its U.S. price."))
        assertFalse(m.displayText, m.displayText.contains("Mr.\n") || m.displayText.contains("Dr.\n"))
        assertFalse(m.displayText, m.displayText.contains("e.g.\n") || m.displayText.contains("U.S.\n"))
        assertTrue(m.displayText, m.displayText.contains("studio.\nThey"))
        val byNorm = m.tokens.associateBy { it.norm }
        assertTrue("mister" in byNorm && "doctor" in byNorm)
        assertFalse(byNorm.getValue("smith").anchor)
        assertFalse(byNorm.getValue("jones").anchor)
        assertTrue(byNorm.getValue("they").anchor)
        val t = tracker(m)
        hear(t, "MISTER SMITH MET DOCTOR JONES AT THE STUDIO THEY TALKED ABOUT THE NEW CAMERA E G ITS U S PRICE", mutableListOf())
        assertEquals(m.spokenTokens, t.position)
    }

    @Test
    fun curlyQuotesStartLines() {
        val m = build(listOf("She said: “Stop filming.” ‘Why?’ he asked."), phraseMode = false)
        val byNorm = m.tokens.associateBy { it.norm }
        assertTrue(byNorm.getValue("stop").anchor)
        assertTrue(byNorm.getValue("why").anchor)
        assertTrue(byNorm.getValue("he").anchor)
        assertFalse(byNorm.getValue("filming").anchor)
    }

    @Test
    fun cyrillicWordsAreTransparent() {
        val m = build(listOf("My grandmother always said: тише едешь, дальше будешь, and she was right about drones too."), false)
        val w = m.tokens.filter { it.spoken }.map { it.norm }
        assertTrue(m.tokens.filter { it.norm in setOf("тише", "едешь", "дальше", "будешь") }.all { it.spoken && it.weight == 0f })
        val t = tracker(m)
        val history = mutableListOf<String>()
        hear(t, "MY GRANDMOTHER ALWAYS SAID TEACHER YEAH DISH DOLLAR BOOTS AND SHE", history)
        assertEquals(idx("she", list = w) + 1, t.position)
        hear(t, "WAS RIGHT ABOUT DRONES TOO", history)
        assertEquals(m.spokenTokens, t.position)
    }

    @Test
    fun hotwordsEnglish() {
        val m = build(listOf("Mr. Smith’s drone isn’t on YouTube since 2025, and don’t film it. Привет, café!"), false)
        val bias = m.biasWords()
        assertTrue(bias.toString(), "mister" in bias && "youtube" in bias && "smith's" in bias && "cafe" in bias)
        assertTrue(bias.toString(), bias.none { it in setOf("don't", "isn't", "2025", "привет", "the", "and") })
        assertEquals(bias, bias.distinct())
        val hot = hotwordList(SpeechLang.EN, bias + listOf("ok", "4k", "naïve", "e-mail"))
        val list = hot.split("/")
        assertTrue(hot, "MISTER" in list && "SMITH'S" in list && "YOUTUBE" in list && "CAFE" in list)
        assertTrue(hot, list.all { h -> h.count { it in 'A'..'Z' } >= 4 && h.all { it in 'A'..'Z' || it == '\'' } })
    }

    @Test
    fun hotwordsRussianAreUnchanged() {
        val m = ScriptLayout.build(
            ScriptDocument("t", "test", TrackerSimulation.RU_SCRIPT.map { Paragraph(it) } + Paragraph("Смотрите мой YouTube и Ёлку!")),
            phraseMode = true,
        )
        // The rule SessionEngine used before English: the written words, lowercased.
        val old = m.tokens.asSequence()
            .filter { it.spoken && it.weight >= 1f }
            .map { m.displayText.substring(it.start, it.end).lowercase() }
            .distinct()
            .take(2000)
            .toList()
        assertEquals(old, m.biasWords())
        val hot = hotwordList(SpeechLang.RU, m.biasWords()).split("/")
        assertTrue(hot.toString(), "съёмки" in hot && "ёлку" in hot && "youtube" !in hot && "ютуб" !in hot)
        assertTrue(hot.toString(), hot.all { h -> h.length >= 4 && h.all { it in 'а'..'я' || it == 'ё' } })
    }

    @Test
    fun defaultsStayRussian() {
        assertEquals(SpeechLang.RU, ScriptModel.EMPTY.lang)
        val m = ScriptLayout.build(ScriptDocument("t", "test", listOf(Paragraph("Hello world"))), phraseMode = false)
        assertEquals(SpeechLang.RU, m.lang)
        assertEquals(SpeechLang.EN, model.lang)
        assertEquals(0, ScriptTracker(emptyList()).size)
    }

    @Test
    fun longScriptStaysFast() {
        val big = build(List(40) { TrackerSimulation.EN_SCRIPT }.flatten())
        val t = tracker(big)
        val history = mutableListOf<String>()
        val started = System.nanoTime()
        hear(t, said(big).take(300), history)
        val msPerUpdate = (System.nanoTime() - started) / 1e6 / 300
        assertEquals(300, t.position)
        assertTrue("%.1f ms per update".format(msPerUpdate), msPerUpdate < 25.0)
    }

    /** Reads the first [n] spoken words of the main script. */
    private fun read(t: ScriptTracker, n: Int, history: MutableList<String>) = hear(t, said().take(n), history)
}
