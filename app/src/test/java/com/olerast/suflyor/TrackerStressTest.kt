package com.olerast.suflyor

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.ScriptModel
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.script.Token
import com.olerast.suflyor.track.ScriptTracker
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Words of the recognizer's output for one spoken token (its script text is [surface]); draws one number from the random. */
typealias Hearing = (rnd: Random, token: Token, surface: String) -> List<String>

/**
 * Simulated reading sessions, the same for every language: the seeds and the segment logic of the Russian
 * ScriptTrackerTest.randomSessionsStayOnTrack and wordsNeededToFindANeighbouringLine.
 */
class TrackerSimulation(val lang: SpeechLang, lines: List<String>, private val offTalk: List<String>) {
    val model: ScriptModel =
        ScriptLayout.build(ScriptDocument("t", "test", lines.map { Paragraph(it) }), phraseMode = true, lang = lang)

    private fun read(t: ScriptTracker, said: List<String>, history: MutableList<String>) {
        for (w in said) {
            history += lang.words(w)
            t.onHypothesis(history.takeLast(16))
        }
    }

    /**
     * 200 sessions of 6 segments each: keep reading, skip ahead, go back, or talk off script; after every 8-word
     * segment the tracker must be where the reader is. Returns (segments off track, segments).
     */
    fun offTrack(hear: Hearing): Pair<Int, Int> {
        val spoken = model.tokens.filter { it.spoken }
        val rnd = Random(42)
        var off = 0
        var checks = 0
        val len = 8
        repeat(200) {
            val t = ScriptTracker(model.tokens, lang)
            val history = mutableListOf<String>()
            var truePos = 0
            repeat(6) {
                val r = rnd.nextInt(100)
                when {
                    r < 55 -> Unit
                    r < 72 -> truePos += 5 + rnd.nextInt(36)
                    r < 90 -> truePos -= 3 + rnd.nextInt(28)
                    else -> read(t, offTalk, history)
                }
                truePos = truePos.coerceIn(0, spoken.size - len)
                val heard = spoken.subList(truePos, truePos + len).flatMap { tok ->
                    hear(rnd, tok, model.displayText.substring(tok.start, tok.end))
                }
                read(t, heard, history)
                truePos += len
                checks++
                if (kotlin.math.abs(t.position - truePos) > 1) off++
            }
        }
        return off to checks
    }

    /**
     * Reader is mid-line and starts a line up to two lines above or below; counts words until the tracker is there.
     * Returns the words needed per attempt, 99 when not found within 6 words.
     */
    fun neighbouringLine(): List<Int> {
        val words = model.tokens.filter { it.spoken }.map { it.norm }
        val anchors = model.tokens.filter { it.spoken }.map { it.anchor }
        val lineStarts = anchors.indices.filter { anchors[it] }
        val rnd = Random(7)
        val needed = mutableListOf<Int>()
        repeat(300) {
            val t = ScriptTracker(model.tokens, lang)
            val history = mutableListOf<String>()
            val from = 6 + rnd.nextInt(words.size - 20)
            for (w in words.take(from)) {
                history += w
                t.onHypothesis(history.takeLast(16))
            }
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
        return needed
    }

    companion object {
        val RU_SCRIPT = """
            Привет! Сегодня я расскажу, как выбрать первый дрон для съёмки путешествий и не переплатить.
            Главное правило простое: сначала решите, что именно вы хотите снимать, а уже потом смотрите на камеру.
            Если вы снимаете пейзажи, важнее стабилизация и время полёта. Если спорт, нужна скорость и хороший трекинг.
            [Показать три модели] Теперь сравним три модели, которые чаще всего покупают новички.
            Первая модель лёгкая и складная, её можно взять в ручную кладь и не регистрировать.
            Вторая модель тяжелее, зато камера у неё снимает в десять бит и лучше вытягивает закаты.
            Третья модель самая дорогая, но у неё есть датчики препятствий со всех сторон.
            Если вы только начинаете, берите первую модель и потратьте сэкономленное на запасные аккумуляторы.
            И напоследок: перед полётом всегда проверяйте правила съёмки в стране, куда едете.
        """.trimIndent().lines()

        /** The Russian script in English, with contractions (straight and curly), a hyphenated word and a stage direction. */
        val EN_SCRIPT = """
            Hi! Today I'll show you how to pick your first drone for travel videos without overpaying.
            The main rule is simple: first decide what exactly you want to film, and only then look at the camera.
            If you shoot landscapes, stabilization and flight time matter more. If it's sports, you need speed and good tracking.
            [Show three models] Now let’s compare three models that beginners buy most often.
            The first model is light and foldable, you can take it in your carry-on and you don’t have to register it.
            The second model is heavier, but its camera shoots ten-bit video and handles sunsets much better.
            The third model is the most expensive one, but it has obstacle sensors on every side.
            If you're just starting out, get the first model and spend the money you saved on spare batteries.
            And finally: before every flight, always check the drone rules in the country you're going to.
        """.trimIndent().lines()

        /** The same with numbers written in digits, which the recognizer says as words ([SPELL]). */
        val EN_SCRIPT_DIGITS = EN_SCRIPT.map {
            it.replace("ten-bit", "10-bit")
                .replace("The third model is the most expensive one,", "The third model came out in 2024 and costs 1,299 dollars,")
        }
        val SPELL = mapOf("10" to "TEN", "2024" to "TWENTY TWENTY FOUR", "1" to "ONE THOUSAND", "299" to "TWO HUNDRED NINETY NINE")

        val EN_OFF_TALK = listOf("SO", "WAIT", "A", "SECOND")

        /** What an English recognizer prints for a word: UPPER CASE with straight apostrophes. */
        fun asrForm(surface: String): String = surface.uppercase().replace('’', '\'')

        /** A wrong English ending: -S, -ED or -ING lost, or an -S added. */
        fun wrongForm(w: String): String = when {
            w.endsWith("S") && !w.endsWith("SS") -> w.dropLast(1)
            w.endsWith("ED") -> w.dropLast(2)
            w.endsWith("ING") -> w.dropLast(3)
            else -> w + "S"
        }

        /** English recognizer noise: 12% of words lost, 15% of words longer than 4 letters with a wrong ending. */
        val EN_NOISE: Hearing = { rnd, _, surface ->
            val x = rnd.nextInt(100)
            val w = asrForm(surface)
            when {
                x < 12 -> emptyList()
                x < 27 && surface.length > 4 -> listOf(wrongForm(w))
                else -> listOf(w)
            }
        }
    }
}

/** The same bounds for every language; subclasses supply the language, script and recognizer noise. */
abstract class TrackerStressTest {
    protected abstract val sim: TrackerSimulation
    protected abstract val noise: Hearing
    protected open val maxOffTrackPercent = 3
    protected open val maxNeighbourWords = 2.6

    private val label get() = "${sim.lang} ${javaClass.simpleName}"

    @Test
    fun randomSessionsStayOnTrack() {
        val (off, checks) = sim.offTrack(noise)
        println("$label random sessions: off track after $off of $checks segments")
        assertTrue("off track after $off of $checks segments", off <= checks * maxOffTrackPercent / 100)
    }

    @Test
    fun wordsNeededToFindANeighbouringLine() {
        val needed = sim.neighbouringLine()
        val avg = needed.filter { it < 99 }.average()
        val lost = needed.count { it == 99 }
        println("$label neighbouring line: %.2f words on average, not found within 6 words: %d of %d".format(avg, lost, needed.size))
        assertTrue("average $avg", avg <= maxNeighbourWords)
        assertTrue("lost $lost", lost <= needed.size / 20)
    }
}

class RussianStressTest : TrackerStressTest() {
    override val sim = TrackerSimulation(SpeechLang.RU, TrackerSimulation.RU_SCRIPT, listOf("так", "сейчас", "секунду"))

    // Every word has a 12% chance to be lost and a 15% chance to get a wrong ending.
    override val noise: Hearing = { rnd, token, _ ->
        val x = rnd.nextInt(100)
        val w = token.norm
        when {
            x < 12 -> emptyList()
            x < 27 && w.length > 4 -> listOf(w.dropLast(1) + "ы")
            else -> listOf(w)
        }
    }
}

class EnglishStressTest : TrackerStressTest() {
    override val sim = TrackerSimulation(SpeechLang.EN, TrackerSimulation.EN_SCRIPT, TrackerSimulation.EN_OFF_TALK)
    override val noise = TrackerSimulation.EN_NOISE
    override val maxNeighbourWords = 2.8

    @Test
    fun cleanSessionsStayOnTrack() {
        val (off, checks) = sim.offTrack { rnd, _, surface ->
            rnd.nextInt(100)
            listOf(TrackerSimulation.asrForm(surface))
        }
        println("EN clean random sessions: off track after $off of $checks segments")
        assertTrue("off track after $off of $checks segments", off == 0)
    }

    @Test
    fun harshNoiseReport() {
        // Function words swapped, contractions expanded, "THE"/"UH" inserted. Mostly reported: about 5.5% of segments
        // end off track in every tracker variant tried, and most of that is lag after jumps (the jump policy, not the
        // language). The bound only catches a collapse.
        val expand = mapOf("DON'T" to "DO NOT", "I'LL" to "I WILL", "IT'S" to "IT IS", "LET'S" to "LET US", "YOU'RE" to "YOU ARE")
        val function = listOf("THE", "A", "IN", "AND", "TO", "OF")
        val (off, checks) = sim.offTrack { rnd, token, surface ->
            var w = TrackerSimulation.asrForm(surface)
            val x = rnd.nextInt(100)
            if (x < 10) {
                emptyList()
            } else {
                if (x < 22 && surface.length > 4) {
                    w = TrackerSimulation.wrongForm(w)
                } else if (x < 27 && token.weight == 0.2f) {
                    w = function[rnd.nextInt(function.size)]
                }
                val out = expand[w]?.takeIf { rnd.nextInt(2) == 0 }?.split(" ")?.toMutableList() ?: mutableListOf(w)
                if (rnd.nextInt(100) < 4) out += listOf("THE", "UH")[rnd.nextInt(2)]
                out
            }
        }
        println("EN harsh noise: off track after $off of $checks segments")
        assertTrue("off track after $off of $checks segments", off <= checks / 10)
    }
}

/** English script with numbers in digits; the recognizer says them as number words. */
class EnglishDigitsStressTest : TrackerStressTest() {
    override val sim = TrackerSimulation(SpeechLang.EN, TrackerSimulation.EN_SCRIPT_DIGITS, TrackerSimulation.EN_OFF_TALK)
    override val maxNeighbourWords = 2.8

    // Numbers are never lost to a wrong ending, but may be lost altogether.
    override val noise: Hearing = { rnd, _, surface ->
        val x = rnd.nextInt(100)
        val w = TrackerSimulation.asrForm(surface)
        val spelled = TrackerSimulation.SPELL[w]
        when {
            x < 12 -> emptyList()
            spelled != null -> spelled.split(" ")
            x < 27 && surface.length > 4 -> listOf(TrackerSimulation.wrongForm(w))
            else -> listOf(w)
        }
    }

    @Test
    fun cleanSessionsStayOnTrack() {
        val (off, checks) = sim.offTrack { rnd, _, surface ->
            rnd.nextInt(100)
            val w = TrackerSimulation.asrForm(surface)
            TrackerSimulation.SPELL[w]?.split(" ") ?: listOf(w)
        }
        println("EN digits clean random sessions: off track after $off of $checks segments")
        assertTrue("off track after $off of $checks segments", off <= checks / 100)
    }
}
