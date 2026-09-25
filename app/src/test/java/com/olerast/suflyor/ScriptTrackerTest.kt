package com.olerast.suflyor

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.TextNorm
import com.olerast.suflyor.track.ScriptTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTrackerTest {
    private val script = """
        Привет! Сегодня я расскажу, как выбрать первый дрон для съёмки путешествий и не переплатить.
        Главное правило простое: сначала решите, что именно вы хотите снимать, а уже потом смотрите на камеру.
        Если вы снимаете пейзажи, важнее стабилизация и время полёта. Если спорт, нужна скорость и хороший трекинг.
        [Показать три модели] Теперь сравним три модели, которые чаще всего покупают новички.
        Первая модель лёгкая и складная, её можно взять в ручную кладь и не регистрировать.
        Вторая модель тяжелее, зато камера у неё снимает в десять бит и лучше вытягивает закаты.
        Третья модель самая дорогая, но у неё есть датчики препятствий со всех сторон.
        Если вы только начинаете, берите первую модель и потратьте сэкономленное на запасные аккумуляторы.
        И напоследок: перед полётом всегда проверяйте правила съёмки в стране, куда едете.
    """.trimIndent()

    private val model = ScriptLayout.build(
        ScriptDocument("t", "test", script.lines().map { Paragraph(it) }),
        phraseMode = true,
    )
    private val words = model.tokens.filter { it.spoken }.map { it.norm }

    /** Simulates a streaming recognizer: the hypothesis grows word by word, the tracker sees the last words. */
    private fun read(tracker: ScriptTracker, said: List<String>, history: MutableList<String>): List<Int> {
        val positions = mutableListOf<Int>()
        for (w in said) {
            history += TextNorm.normalizeWord(w)
            tracker.onHypothesis(history.takeLast(16))
            positions += tracker.position
        }
        return positions
    }

    private fun idx(word: String, from: Int = 0): Int {
        val i = words.subList(from, words.size).indexOf(word)
        require(i >= 0) { "no $word" }
        return from + i
    }

    @Test
    fun followsExactReading() {
        val t = ScriptTracker(model.tokens)
        read(t, words, mutableListOf())
        assertEquals(words.size, t.position)
    }

    @Test
    fun toleratesWrongEndingsDroppedWordsAndFillers() {
        val t = ScriptTracker(model.tokens)
        val noisy = mutableListOf<String>()
        words.forEachIndexed { i, w ->
            when {
                i % 7 == 3 -> Unit // dropped by the recognizer
                i % 5 == 1 && w.length > 5 -> noisy += w.dropLast(1) + "и" // wrong ending
                else -> noisy += w
            }
            if (i % 9 == 4) noisy += "э"
        }
        read(t, noisy, mutableListOf())
        assertTrue("position ${t.position} of ${words.size}", t.position >= words.size - 2)
    }

    @Test
    fun staysPutOnSilenceAndOffScriptTalk() {
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(10), history)
        val before = t.position
        read(t, "ой подождите секундочку телефон звонит извините".split(" "), history)
        assertEquals(before, t.position)
        t.onHypothesis(history.takeLast(16)) // same hypothesis again = silence
        assertEquals(before, t.position)
    }

    @Test
    fun noJumpsOnScatteredCommonScriptWords() {
        // Frequent words of this script, said out of order, must not send the reader anywhere.
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(20), history)
        val before = t.position
        val trail = read(t, "модель и камера если вы модель а у неё и не модель".split(" "), history)
        assertTrue("jumped: $trail", trail.all { it == before })
    }

    @Test
    fun retakeOfTheLineJustRead() {
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        val lineStart = idx("как")
        val lineEnd = idx("путешествий") + 1
        read(t, words.take(lineEnd), history)
        assertEquals(lineEnd, t.position)
        read(t, words.subList(lineStart, lineStart + 4), history)
        assertEquals(lineStart + 4, t.position)
    }

    @Test
    fun retakeOfShortLineStartingWithFunctionWords() {
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        val lineStart = idx("уже") - 1 // "а уже потом смотрите на камеру."
        val sentenceEnd = idx("камеру") + 1
        read(t, words.take(sentenceEnd), history)
        read(t, words.subList(lineStart, lineStart + 5), history)
        assertEquals(lineStart + 5, t.position)
    }

    @Test
    fun retakeFarBack() {
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(idx("регистрировать") + 1), history)
        val restart = idx("главное")
        val trail = read(t, words.subList(restart, restart + 5), history)
        assertEquals(restart + 5, t.position)
        // On the way it never lands anywhere else.
        assertTrue("trail $trail", trail.all { it >= restart || it == trail.first() })
    }

    @Test
    fun skipsABlockFarAhead() {
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(12), history)
        val before = t.position
        val target = idx("третья")
        val trail = read(t, words.subList(target, target + 6), history)
        assertEquals(target + 6, t.position)
        assertTrue("wandered: $trail", trail.all { it == before || it >= target })
    }

    @Test
    fun returnsToPreviousLineAfterTwoWords() {
        // Reading "сначала решите, что именно…", the reader goes back to the line above: "Главное правило простое:".
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(idx("именно")), history)
        val lineStart = idx("главное")
        read(t, words.subList(lineStart, lineStart + 2), history)
        assertEquals(lineStart + 2, t.position)
    }

    @Test
    fun oneWordAndTheStartOfTheNextAreEnoughNearby() {
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(idx("именно")), history)
        val lineStart = idx("главное")
        history += words[lineStart]
        t.onHypothesis(history.takeLast(16))
        t.onHypothesis((history + words[lineStart + 1].take(3)).takeLast(16))
        assertEquals(lineStart + 2, t.position)
    }

    @Test
    fun skipsToNextLineAfterTwoWords() {
        // "Если вы | снимаете пейзажи," — the reader drops the rest of the line and starts the next one.
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(idx("снимаете")), history)
        val next = idx("важнее")
        read(t, words.subList(next, next + 2), history)
        assertEquals(next + 2, t.position)
    }

    @Test
    fun skipsAPhraseNearby() {
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(12), history)
        val target = idx("пейзажи")
        read(t, words.subList(target, target + 5), history)
        assertEquals(target + 5, t.position)
    }

    @Test
    fun randomSessionsStayOnTrack() {
        // 200 sessions of 6 segments each: keep reading, skip ahead, go back, or talk off script; after every
        // 8-word segment the tracker must be where the reader is.
        val rnd = java.util.Random(42)
        var off = 0
        var checks = 0
        val len = 8
        repeat(200) {
            val t = ScriptTracker(model.tokens)
            val history = mutableListOf<String>()
            var truePos = 0
            repeat(6) {
                val r = rnd.nextInt(100)
                when {
                    r < 55 -> Unit
                    r < 72 -> truePos += 5 + rnd.nextInt(36)
                    r < 90 -> truePos -= 3 + rnd.nextInt(28)
                    else -> read(t, listOf("так", "сейчас", "секунду"), history)
                }
                truePos = truePos.coerceIn(0, words.size - len)
                // Recognizer noise: every word has a 12% chance to be lost and a 15% chance to get a wrong ending.
                val heard = words.subList(truePos, truePos + len).mapNotNull { w ->
                    val x = rnd.nextInt(100)
                    when {
                        x < 12 -> null
                        x < 27 && w.length > 4 -> w.dropLast(1) + "ы"
                        else -> w
                    }
                }
                read(t, heard, history)
                truePos += len
                checks++
                if (kotlin.math.abs(t.position - truePos) > 1) off++
            }
        }
        println("random sessions: off track after $off of $checks segments")
        assertTrue("off track after $off of $checks segments", off <= checks * 3 / 100)
    }

    @Test
    fun wordsNeededToFindANeighbouringLine() {
        // Reader is mid-line and starts a line up to two lines above or below. Count words until the tracker is there.
        val anchors = model.tokens.filter { it.spoken }.map { it.anchor }
        val lineStarts = anchors.indices.filter { anchors[it] }
        val rnd = java.util.Random(7)
        val needed = mutableListOf<Int>()
        repeat(300) {
            val t = ScriptTracker(model.tokens)
            val history = mutableListOf<String>()
            val from = 6 + rnd.nextInt(words.size - 20)
            read(t, words.take(from), history)
            val here = lineStarts.indexOfLast { it <= from }
            val targetLine = (here + listOf(-2, -1, 1, 2)[rnd.nextInt(4)]).coerceIn(0, lineStarts.size - 1)
            val target = lineStarts[targetLine]
            if (kotlin.math.abs(target - from) <= 3 || target + 6 > words.size) return@repeat
            var n = 0
            for (k in 0 until 6) {
                history += words[target + k]
                t.onHypothesis(history.takeLast(16))
                n = k + 1
                if (t.position == target + k + 1) break
            }
            needed += if (t.position == target + n) n else 99
        }
        val avg = needed.filter { it < 99 }.average()
        val lost = needed.count { it == 99 }
        println("neighbouring line: %.2f words on average, not found within 6 words: %d of %d".format(avg, lost, needed.size))
        assertTrue("average $avg", avg <= 2.6)
        assertTrue("lost $lost", lost <= needed.size / 20)
    }

    @Test
    fun partiallySpokenLastWordMovesEarly() {
        val t = ScriptTracker(model.tokens)
        val history = mutableListOf<String>()
        read(t, words.take(6), history)
        assertEquals(6, t.position)
        t.onHypothesis((history + words[6].take(3)).takeLast(16))
        assertEquals(7, t.position)
        history += words[6]
        t.onHypothesis(history.takeLast(16))
        assertEquals(7, t.position)
    }

    @Test
    fun stageDirectionsAreNotExpected() {
        assertTrue(model.tokens.any { !it.spoken && it.norm == "показать" })
        assertTrue("показать" !in words)
    }

    @Test
    fun latinWordsMatchRussianRecognition() {
        assertEquals("ютуб", TextNorm.normalizeWord("YouTube"))
        assertEquals("инстаграм", TextNorm.normalizeWord("Instagram"))
        assertEquals("еж", TextNorm.normalizeWord("Ёж"))
        assertEquals("замок", TextNorm.normalizeWord("замо́к"))
        assertTrue(TextNorm.similarity("сделал", "сделали") >= ScriptTracker.MATCH_SIM)
        assertTrue(TextNorm.similarity("дома", "домой") >= ScriptTracker.MATCH_SIM)
        assertTrue(TextNorm.similarity("камера", "дрон") < ScriptTracker.MATCH_SIM)
    }
}
