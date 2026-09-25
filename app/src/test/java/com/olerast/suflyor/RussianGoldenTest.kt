package com.olerast.suflyor

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.ScriptModel
import com.olerast.suflyor.script.TextNorm
import com.olerast.suflyor.track.ScriptTracker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Russian behaviour pinned byte for byte: layout (line breaks, norm, weight, anchor, spoken of every token) and the
 * tracker's position after every heard word in the random and neighbouring-line simulations of [ScriptTrackerTest].
 *
 * The golden file was written by this test from the code before English support (commit fbf577c). To regenerate it
 * after an intended change of Russian behaviour, delete src/test/resources/golden/ru_golden.txt, run the test and copy
 * build/golden/ru_golden.txt over it.
 */
class RussianGoldenTest {
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

    /** Latin words, numbers, abbreviations and every kind of quote: what English support touches in the shared code. */
    private val mixed = listOf(
        "Мы выложили ролик на YouTube и в Instagram 12 мая 2025 года, т.е. ровно в срок.",
        "Г. Иванов сказал: «Don’t worry» — и “всё будет ok”. Потом ‘тишина’ (и пауза)… Mr. Smith кивнул!",
        "А дальше? Мы снимали 4K видео в 90-х, e.g. на плёнку; it’s fine, I'll be back and then we're done.",
        "Бюджет проекта составил 12 345 678 рублей, а срок сдачи перенесли на 2027 год без штрафов.",
    )

    private fun model(lines: List<String>, phraseMode: Boolean = true): ScriptModel =
        ScriptLayout.build(ScriptDocument("t", "test", lines.map { Paragraph(it) }), phraseMode = phraseMode)

    private fun layoutDump(name: String, m: ScriptModel): String = buildString {
        append("## layout $name\n")
        for (line in m.displayText.split('\n')) append("> ").append(line).append('\n')
        for (t in m.tokens) {
            append(m.displayText, t.start, t.end)
            append(' ').append(t.norm).append(':').append(t.weight).append(':').append(t.anchor).append(':').append(t.spoken)
            append('\n')
        }
    }

    /** Same simulation as ScriptTrackerTest.randomSessionsStayOnTrack, recording the position after every word. */
    private fun randomTrail(m: ScriptModel): String = buildString {
        append("## random sessions\n")
        val words = m.tokens.filter { it.spoken }.map { it.norm }
        val rnd = java.util.Random(42)
        val len = 8
        repeat(200) {
            val t = ScriptTracker(m.tokens)
            val history = mutableListOf<String>()
            val trail = StringBuilder()
            fun read(said: List<String>) {
                for (w in said) {
                    history += TextNorm.normalizeWord(w)
                    t.onHypothesis(history.takeLast(16))
                    trail.append(t.position).append(' ')
                }
            }
            var truePos = 0
            repeat(6) {
                val r = rnd.nextInt(100)
                when {
                    r < 55 -> Unit
                    r < 72 -> truePos += 5 + rnd.nextInt(36)
                    r < 90 -> truePos -= 3 + rnd.nextInt(28)
                    else -> read(listOf("так", "сейчас", "секунду"))
                }
                truePos = truePos.coerceIn(0, words.size - len)
                val heard = words.subList(truePos, truePos + len).mapNotNull { w ->
                    val x = rnd.nextInt(100)
                    when {
                        x < 12 -> null
                        x < 27 && w.length > 4 -> w.dropLast(1) + "ы"
                        else -> w
                    }
                }
                read(heard)
                truePos += len
                trail.append("| ")
            }
            append(trail.trimEnd()).append('\n')
        }
    }

    /** Same simulation as ScriptTrackerTest.wordsNeededToFindANeighbouringLine: position after every word. */
    private fun neighbourTrail(m: ScriptModel): String = buildString {
        append("## neighbouring lines\n")
        val words = m.tokens.filter { it.spoken }.map { it.norm }
        val anchors = m.tokens.filter { it.spoken }.map { it.anchor }
        val lineStarts = anchors.indices.filter { anchors[it] }
        val rnd = java.util.Random(7)
        repeat(300) {
            val t = ScriptTracker(m.tokens)
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
            append(from).append(" -> ").append(target).append(':')
            for (k in 0 until 6) {
                history += words[target + k]
                t.onHypothesis(history.takeLast(16))
                append(' ').append(t.position)
            }
            append('\n')
        }
    }

    /** Recognizer-like text in and out of the Russian normalization, and a noisy read of the mixed script. */
    private fun mixedTrail(m: ScriptModel): String = buildString {
        append("## mixed script\n")
        val asr = "мы выложили ролик на ютуб и в инстаграм двенадцатого мая две тысячи двадцать пятого года то есть ровно в срок " +
            "иванов сказал донт вори и всё будет окей потом тишина и пауза мистер смит кивнул а дальше мы снимали " +
            "четыре к видео в девяностых на плёнку итс файн айл би бэк энд зен виар дан бюджет проекта составил " +
            "двенадцать миллионов рублей а срок сдачи перенесли на две тысячи двадцать седьмой год без штрафов"
        val heard = TextNorm.words(asr)
        append(heard.joinToString(" ")).append('\n')
        val t = ScriptTracker(m.tokens)
        val history = mutableListOf<String>()
        for (w in heard) {
            history += w
            val u = t.onHypothesis(history.takeLast(16))
            append(u.position).append(' ')
        }
        append('\n')
    }

    private fun actual(): String {
        val drone = model(script.lines())
        val mixedPhrases = model(mixed)
        return listOf(
            layoutDump("drone, phrases", drone),
            layoutDump("drone, paragraphs", model(script.lines(), phraseMode = false)),
            layoutDump("mixed, phrases", mixedPhrases),
            layoutDump("mixed, paragraphs", model(mixed, phraseMode = false)),
            randomTrail(drone),
            neighbourTrail(drone),
            mixedTrail(mixedPhrases),
        ).joinToString("")
    }

    @Test
    fun russianLayoutAndTrackingAreUnchanged() {
        val got = actual()
        val expected = javaClass.getResourceAsStream("/golden/ru_golden.txt")
            ?.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n") }
        if (expected == null) {
            val out = File("build/golden/ru_golden.txt")
            out.absoluteFile.parentFile?.mkdirs()
            out.writeText(got)
            throw AssertionError("no golden file; wrote ${out.absolutePath}")
        }
        if (expected != got) {
            val out = File("build/golden/ru_golden.actual.txt")
            out.absoluteFile.parentFile?.mkdirs()
            out.writeText(got)
        }
        assertEquals(expected, got)
    }
}
