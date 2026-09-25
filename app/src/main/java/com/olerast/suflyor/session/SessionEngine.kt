package com.olerast.suflyor.session

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.olerast.suflyor.App
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.overlay.PrompterAccessibilityService
import com.olerast.suflyor.script.ScriptModel
import com.olerast.suflyor.script.TextNorm
import com.olerast.suflyor.speech.AsrEngine
import com.olerast.suflyor.speech.AsrUpdate
import com.olerast.suflyor.speech.AudioCapture
import com.olerast.suflyor.speech.SherpaAsr
import com.olerast.suflyor.track.ScriptTracker
import java.util.concurrent.CopyOnWriteArrayList

/** Microphone -> recognizer -> tracker pipeline plus diagnostics. One session at a time, in-app or over other apps. */
class SessionEngine(private val app: App) : AudioCapture.Listener {
    enum class Mode { IDLE, IN_APP, OVERLAY }

    enum class Scroll { VOICE, AUTO }

    data class State(
        val mode: Mode = Mode.IDLE,
        val starting: Boolean = false,
        val listening: Boolean = false,
        val paused: Boolean = false,
        val scroll: Scroll = Scroll.VOICE,
        /** True while voice mode falls back to timed scrolling because the microphone gives only silence. */
        val autoFallback: Boolean = false,
        val levelDb: Float = -120f,
        val silencedBySystem: Boolean? = null,
        val digitalSilence: Boolean = false,
        val partial: String = "",
        val lastFinal: String = "",
        val nextToken: Int = 0,
        val position: Int = 0,
        val total: Int = 0,
        val recordings: List<RecordingMonitor.Rec> = emptyList(),
        val foregroundApp: String? = null,
        val asrStatus: String = "модель не загружена",
        val error: String? = null,
        /** Seconds left in the 3-2-1 before timed scrolling starts; 0 when there is none. */
        val countdown: Int = 0,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    var state = State()
        private set

    private val lock = Any()
    private var model: ScriptModel = ScriptModel.EMPTY
    private var tracker = ScriptTracker(emptyList())

    @Volatile
    private var asr: AsrEngine? = null
    private var asrLoading = false
    private var capture: AudioCapture? = null
    private var monitor: RecordingMonitor? = null

    private val finalWords = ArrayDeque<String>()
    private var partialWords: List<String> = emptyList()

    /** What the reader sees: the tracker position plus a small lead while speaking; never moves back by itself. */
    private var displayPos = 0
    private var lastSpeechAt = 0L

    // Capture-thread statistics for the periodic journal line.
    private var statFrames = 0
    private var statZeroFrames = 0
    private var statDbSum = 0.0
    private var statDbMax = -120f
    private var statWords = 0
    private var lastSilencedCheck = 0L
    private var zeroSince = 0L

    private var autoCarry = 0.0
    private var lastTick = 0L
    private var lastStatLog = 0L
    private var lastRecordings: List<String> = emptyList()

    fun addListener(l: (State) -> Unit) {
        listeners += l
        l(state)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners -= l
    }

    private fun update(f: State.() -> State) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            state = state.f()
            listeners.forEach { it(state) }
        } else {
            main.post { update(f) }
        }
    }

    // ---- script -------------------------------------------------------------------------------------------------

    fun setScript(m: ScriptModel) {
        synchronized(lock) {
            model = m
            tracker = ScriptTracker(m.tokens)
            finalWords.clear()
            partialWords = emptyList()
            displayPos = 0
        }
        asr?.setBiasWords(biasWords(m))
        update { copy(nextToken = tracker.nextTokenIndex(), position = 0, total = tracker.size, partial = "", lastFinal = "") }
    }

    fun jumpToToken(tokenIndex: Int) = moveTracker { it.jumpToToken(tokenIndex) }

    fun restart() {
        moveTracker { it.reset(0) }
        if (state.scroll == Scroll.AUTO) startCountdown()
    }

    private var countdownEndsAt = 0L

    /** 3-2-1 before timed scrolling (voice following needs none: it starts when the reader does). */
    private fun startCountdown() {
        val n = app.settings.countdownSec
        if (n <= 0 || !state.listening) return
        countdownEndsAt = SystemClock.elapsedRealtime() + n * 1000L
        update { copy(countdown = n) }
    }

    /** Moves by display lines: back goes to the start of the current line first, then to previous lines. */
    fun stepLine(delta: Int) = moveTracker { t ->
        val tokens = model.tokens
        if (tokens.isEmpty()) return@moveTracker
        val text = model.displayText
        val lineStarts = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n' && i + 1 < text.length && text[i + 1] != '\n') lineStarts += i + 1 }
        val cur = t.nextTokenIndex().coerceAtMost(tokens.size - 1)
        val offset = tokens[cur].start
        var line = lineStarts.indexOfLast { it <= offset }.coerceAtLeast(0)
        val firstTokenOfLine = tokens.indexOfFirst { it.start >= lineStarts[line] }
        line = when {
            delta < 0 && firstTokenOfLine < cur -> line
            else -> (line + delta).coerceIn(0, lineStarts.size - 1)
        }
        val target = tokens.indexOfFirst { it.start >= lineStarts[line] && it.spoken }
        if (target >= 0) t.jumpToToken(target)
    }

    private fun moveTracker(action: (ScriptTracker) -> Unit) {
        val pos: Int
        val next: Int
        synchronized(lock) {
            action(tracker)
            finalWords.clear()
            partialWords = emptyList()
            asr?.reset()
            pos = tracker.position
            displayPos = pos
            next = tracker.nextTokenIndex()
        }
        autoCarry = 0.0
        update { copy(position = pos, nextToken = next) }
    }

    private fun biasWords(m: ScriptModel): List<String> = m.tokens.asSequence()
        .filter { it.spoken && it.weight >= 1f }
        .map { m.displayText.substring(it.start, it.end).lowercase() }
        .distinct()
        .take(2000)
        .toList()

    // ---- session ------------------------------------------------------------------------------------------------

    fun start(mode: Mode) {
        if (state.mode != Mode.IDLE) stop()
        update { copy(mode = mode, starting = true, error = null, paused = false, autoFallback = false) }
        Thread({
            val asrError = ensureAsr()
            main.post { if (state.mode == mode && state.starting) continueStart(mode, asrError) }
        }, "asr-load").start()
    }

    private fun ensureAsr(): String? {
        synchronized(this) {
            if (asr != null) return null
            if (asrLoading) return "Модель ещё загружается"
            asrLoading = true
        }
        update { copy(asrStatus = "загружаю модель…") }
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val engine = SherpaAsr.create(app)
            engine.setBiasWords(biasWords(model))
            asr = engine
            val ms = SystemClock.elapsedRealtime() - t0
            DiagLog.i("Распознавание загружено за $ms мс: ${engine.description}")
            update { copy(asrStatus = "готово (${engine.description})") }
            null
        } catch (t: Throwable) {
            DiagLog.e("Не удалось загрузить распознавание", t)
            update { copy(asrStatus = "ошибка: ${t.message}") }
            "Распознавание не загрузилось: ${t.message}"
        } finally {
            synchronized(this) { asrLoading = false }
        }
    }

    private fun continueStart(mode: Mode, asrError: String?) {
        val s = app.settings
        val cap = AudioCapture(s.audioSource, s.sampleRate, this)
        val err = cap.start()
        if (err != null) {
            DiagLog.e(err)
            update { copy(mode = Mode.IDLE, starting = false, listening = false, error = err) }
            return
        }
        capture = cap
        resetStats()
        monitor = RecordingMonitor(app, { cap.audioSessionId }) { onRecordings(it) }.also { it.start() }
        val a11y = PrompterAccessibilityService.instance != null
        DiagLog.i(
            "Старт: ${if (mode == Mode.OVERLAY) "поверх других приложений" else "внутри приложения"}, " +
                "источник ${AudioCapture.sourceName(s.audioSource)}, ${s.sampleRate} Гц, " +
                "служба спецвозможностей ${if (a11y) "ВКЛ" else "выкл"}, " +
                "распознавание: ${asr?.description ?: "нет"}",
        )
        update { copy(starting = false, listening = true, error = asrError) }
        lastTick = SystemClock.elapsedRealtime()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    fun stop() {
        main.removeCallbacks(ticker)
        capture?.stop()
        capture = null
        monitor?.stop()
        monitor = null
        if (state.mode != Mode.IDLE) DiagLog.i("Сессия остановлена")
        lastRecordings = emptyList()
        update {
            copy(
                mode = Mode.IDLE, starting = false, listening = false, partial = "", levelDb = -120f,
                silencedBySystem = null, digitalSilence = false, autoFallback = false, recordings = emptyList(),
                countdown = 0,
            )
        }
        countdownEndsAt = 0L
    }

    fun togglePause() {
        val paused = !state.paused
        update { copy(paused = paused) }
        DiagLog.i(if (paused) "Пауза" else "Продолжаем")
        if (!paused && state.scroll == Scroll.AUTO) startCountdown()
    }

    fun setScroll(scroll: Scroll) {
        autoCarry = 0.0
        update { copy(scroll = scroll) }
        DiagLog.i(if (scroll == Scroll.AUTO) "Прокрутка: по скорости ${app.settings.autoScrollWpm} слов/мин" else "Прокрутка: по голосу")
        if (scroll == Scroll.AUTO) startCountdown() else {
            countdownEndsAt = 0L
            update { copy(countdown = 0) }
        }
    }

    fun setForegroundApp(pkg: String) {
        if (pkg == state.foregroundApp) return
        if (state.mode != Mode.IDLE) DiagLog.i("На экране: $pkg")
        update { copy(foregroundApp = pkg) }
    }

    // ---- audio thread -------------------------------------------------------------------------------------------

    override fun onAudio(samples: FloatArray, sampleRate: Int, rmsDb: Float, digitalSilence: Boolean) {
        val now = SystemClock.elapsedRealtime()
        statFrames++
        if (digitalSilence) statZeroFrames++
        statDbSum += rmsDb
        if (rmsDb > statDbMax) statDbMax = rmsDb
        zeroSince = if (digitalSilence) (if (zeroSince == 0L) now else zeroSince) else 0L

        var silenced: Boolean? = state.silencedBySystem
        if (now - lastSilencedCheck > 500) {
            lastSilencedCheck = now
            val s = capture?.isSilencedBySystem
            if (s != null && s != silenced) {
                DiagLog.i(if (s) "Android заглушил наш микрофон (приоритет у другого приложения)" else "Микрофон снова слышит")
            }
            silenced = s
        }

        val engine = asr
        if (engine != null) {
            val upd = try {
                engine.accept(samples, sampleRate)
            } catch (t: Throwable) {
                DiagLog.e("Ошибка распознавания", t)
                null
            }
            if (upd != null) onAsr(upd)
        }
        val fallback = state.scroll == Scroll.VOICE && (silenced == true || (zeroSince != 0L && now - zeroSince > 2000))
        update { copy(levelDb = rmsDb, digitalSilence = digitalSilence, silencedBySystem = silenced, autoFallback = fallback) }
    }

    private fun onAsr(upd: AsrUpdate) {
        val words = TextNorm.words(upd.text)
        var moved: ScriptTracker.Update? = null
        val pos: Int
        val next: Int
        val now = SystemClock.elapsedRealtime()
        if (!upd.isFinal) lastSpeechAt = now
        synchronized(lock) {
            if (upd.isFinal) {
                words.forEach { finalWords.addLast(it) }
                while (finalWords.size > 16) finalWords.removeFirst()
                partialWords = emptyList()
            } else {
                partialWords = words
            }
            val before = tracker.position
            if (!state.paused && state.scroll == Scroll.VOICE) {
                moved = tracker.onHypothesis(finalWords.toList() + partialWords)
            }
            pos = tracker.position
            if (pos < before) displayPos = pos
            val maxLead = app.settings.leadWords
            val lead = if (now - lastSpeechAt < SPEAKING_MS) maxLead else 0
            displayPos = maxOf(displayPos, minOf(pos + lead, tracker.size)).coerceAtMost(pos + maxLead)
            next = tracker.tokenIndexAt(displayPos)
        }
        moved?.let { u ->
            if (u.moved && u.reason != "follow") {
                DiagLog.i("Трекер: ${u.reason} → слово ${u.position}/${tracker.size} (совпало ${u.matches}, счёт %.1f)".format(u.score))
            }
        }
        if (upd.isFinal && upd.text.isNotBlank()) {
            statWords += words.size
            DiagLog.i("Услышал: ${upd.text}")
        }
        update {
            copy(
                partial = if (upd.isFinal) "" else upd.text,
                lastFinal = if (upd.isFinal && upd.text.isNotBlank()) upd.text else lastFinal,
                position = pos,
                nextToken = next,
            )
        }
    }

    override fun onCaptureError(message: String) {
        DiagLog.e(message)
        update { copy(error = message) }
    }

    // ---- main thread --------------------------------------------------------------------------------------------

    private fun onRecordings(recs: List<RecordingMonitor.Rec>) {
        val described = recs.map { it.describe() }.sorted()
        if (described != lastRecordings) {
            lastRecordings = described
            DiagLog.i("Запись звука на устройстве: " + if (described.isEmpty()) "никто" else described.joinToString(" | "))
        }
        val ours = recs.firstOrNull { it.ours }
        update { copy(recordings = recs, silencedBySystem = ours?.silenced ?: silencedBySystem) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick) / 1000.0
            lastTick = now
            val st = state
            if (countdownEndsAt > 0L) {
                val left = ((countdownEndsAt - now + 999) / 1000).toInt()
                if (left <= 0) {
                    countdownEndsAt = 0L
                    autoCarry = 0.0
                    update { copy(countdown = 0) }
                } else if (left != st.countdown) {
                    update { copy(countdown = left) }
                }
            }
            if (st.listening && !st.paused && countdownEndsAt == 0L && (st.scroll == Scroll.AUTO || st.autoFallback)) {
                autoCarry += app.settings.autoScrollWpm / 60.0 * dt
                if (autoCarry >= 1.0) {
                    val steps = autoCarry.toInt()
                    autoCarry -= steps
                    val pos: Int
                    val next: Int
                    synchronized(lock) {
                        tracker.reset(tracker.position + steps)
                        pos = tracker.position
                        displayPos = pos
                        next = tracker.nextTokenIndex()
                    }
                    update { copy(position = pos, nextToken = next) }
                }
            }
            if (now - lastStatLog >= 5000 && st.listening) {
                lastStatLog = now
                logStats()
            }
            main.postDelayed(this, 100)
        }
    }

    private fun resetStats() {
        statFrames = 0
        statZeroFrames = 0
        statDbSum = 0.0
        statDbMax = -120f
        statWords = 0
        zeroSince = 0L
        lastStatLog = SystemClock.elapsedRealtime()
    }

    private companion object {
        /** A partial result within this time means the reader is still talking. */
        const val SPEAKING_MS = 900L
    }

    private fun logStats() {
        if (statFrames == 0) return
        val avg = statDbSum / statFrames
        val zeroPct = statZeroFrames * 100 / statFrames
        DiagLog.i(
            "Микрофон за 5 с: средний %.0f дБ, пик %.0f дБ, нулевых кадров %d%%, слов %d, позиция %d/%d%s".format(
                avg, statDbMax, zeroPct, statWords, state.position, state.total,
                state.foregroundApp?.let { ", на экране $it" } ?: "",
            ),
        )
        statFrames = 0
        statZeroFrames = 0
        statDbSum = 0.0
        statDbMax = -120f
        statWords = 0
    }
}
