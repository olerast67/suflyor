package com.olerast.suflyor.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.olerast.suflyor.App
import com.olerast.suflyor.doc.PlainTextImporter
import com.olerast.suflyor.doc.TextDecoding
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.ScriptModel
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.script.biasWords
import com.olerast.suflyor.speech.AsrUpdate
import com.olerast.suflyor.speech.SherpaAsr
import com.olerast.suflyor.track.ScriptTracker
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only: runs the recognizer (and, with a script, the tracker) over a WAV file as fast as it can.
 *
 *     adb push take.wav take.txt /data/local/tmp/
 *     adb shell run-as com.olerast.suflyor.debug cp /data/local/tmp/take.wav /data/local/tmp/take.txt files/
 *     adb shell am broadcast -n com.olerast.suflyor.debug/com.olerast.suflyor.bench.AsrBench -a com.olerast.suflyor.BENCH \
 *         --es wav take.wav --es lang en [--es script take.txt] [--ez hotwords true|false]
 *     adb logcat -s SuflyorBench
 *
 * The WAV is 16-bit PCM, mono or stereo (channels are averaged), at any rate: sherpa-onnx resamples to 16 kHz.
 * It is fed in 100 ms chunks, then 1.5 s of silence closes the last utterance. Reported: model load time, real-time
 * factor, audio time of the first non-empty partial, the recognized text; with a script, the tracker is driven exactly
 * like SessionEngine (last 16 final words + the current partial) and the log gives the final position against the
 * script's spoken words and the updates where new words were heard but the position did not move ("lagged").
 * Hotwords follow the app setting unless --ez hotwords is given; with a script they are the script's words.
 *
 * A background broadcast must finish within a minute: after [BROADCAST_BUDGET_MS] the broadcast is released and the
 * run goes on only while the app process stays awake, so keep Suflyor dev open for long files.
 */
class AsrBench : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!finished.get()) Log.w(TAG, "still running: broadcast released, keep the app open until the summary")
            finish()
        }, BROADCAST_BUDGET_MS)
        val app = context.applicationContext
        Thread({
            try {
                run(app, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "benchmark failed", t)
            } finally {
                finish()
            }
        }, "asr-bench").start()
    }

    private fun run(context: Context, intent: Intent) {
        val wavName = intent.getStringExtra("wav")
        if (wavName == null) {
            Log.e(TAG, "usage: --es wav <file in filesDir> --es lang en|ru [--es script <file>] [--ez hotwords true|false]")
            return
        }
        val scriptName = intent.getStringExtra("script")
        val doc = scriptName?.let { PlainTextImporter.parse(TextDecoding.decode(fileIn(context, it).readBytes()), it) }
        val lang = SpeechLang.fromCode(intent.getStringExtra("lang")) ?: doc?.let { SpeechLang.detect(it) }
        if (lang == null) {
            Log.e(TAG, "--es lang en|ru is required without a script")
            return
        }
        val hot = if (intent.hasExtra("hotwords")) intent.getBooleanExtra("hotwords", true) else App.instance.settings.useHotwords
        val wav = readWav(fileIn(context, wavName))
        val audioSec = wav.samples.size.toDouble() / wav.sampleRate
        log(
            "wav $wavName: %.1f s, %d Hz, %d ch; lang %s, hotwords %s"
                .format(Locale.ROOT, audioSec, wav.sampleRate, wav.channels, lang.code, hot),
        )

        val t0 = SystemClock.elapsedRealtime()
        val asr = SherpaAsr.create(context, lang, hot)
        log("init ${SystemClock.elapsedRealtime() - t0} ms: ${asr.description}")
        try {
            val model = doc?.let { ScriptLayout.build(it, phraseMode = true, lang = lang) }
            if (model != null) {
                val bias = model.biasWords()
                asr.setBiasWords(bias)
                log("script $scriptName: ${model.spokenTokens} spoken words, ${bias.size} bias words")
            }
            Run(asr.lang, model).feed(asr, wav, audioSec)
        } finally {
            asr.release()
        }
    }

    /** One pass over the audio: what SessionEngine.onAsr does with every recognizer update, plus the statistics. */
    private class Run(private val lang: SpeechLang, model: ScriptModel?) {
        private val tracker = model?.let { ScriptTracker(it.tokens, it.lang) }
        private val finalWords = ArrayDeque<String>()
        private var partialWords: List<String> = emptyList()
        private val finals = StringBuilder()
        private var lastPartial = ""
        private var firstPartialAt = -1.0
        private var heardWords = 0
        private var finalCount = 0
        private var updates = 0
        private var lagged = 0
        private var lagRun = 0
        private var longestLag = 0

        fun feed(asr: SherpaAsr, wav: Wav, audioSec: Double) {
            val rate = wav.sampleRate
            val chunk = maxOf(1, rate / 10)
            val n = wav.samples.size
            val started = SystemClock.elapsedRealtimeNanos()
            var fed = 0
            while (fed < n) {
                val end = minOf(n, fed + chunk)
                asr.accept(wav.samples.copyOfRange(fed, end), rate)?.let { onUpdate(it, end.toDouble() / rate) }
                fed = end
            }
            val decodeSec = (SystemClock.elapsedRealtimeNanos() - started) / 1e9
            // The endpoint needs 0.8 s of silence after speech: close the last utterance like a pause would.
            repeat(15) { k -> asr.accept(FloatArray(chunk), rate)?.let { onUpdate(it, audioSec + (k + 1) * 0.1) } }

            log("decode %.2f s for %.1f s of audio: real-time factor %.3f".format(Locale.ROOT, decodeSec, audioSec, decodeSec / audioSec))
            log(if (firstPartialAt < 0) "no speech recognized" else "first partial at %.2f s of audio".format(Locale.ROOT, firstPartialAt))
            val text = (finals.toString() + lastPartial).trim()
            text.chunked(1000).forEachIndexed { i, part -> log((if (i == 0) "text: " else "      ") + part) }
            val t = tracker ?: return
            log(
                "tracker: position %d of %d spoken words, %d words recognized; %d updates, lagged %d (longest run %d)"
                    .format(Locale.ROOT, t.position, t.size, heardWords, updates, lagged, longestLag),
            )
        }

        private fun onUpdate(upd: AsrUpdate, atSec: Double) {
            if (upd.text.isNotEmpty() && firstPartialAt < 0) firstPartialAt = atSec
            val words = lang.words(upd.text)
            if (upd.isFinal) {
                finals.append(upd.text).append(' ')
                lastPartial = ""
                words.forEach { finalWords.addLast(it) }
                while (finalWords.size > 16) finalWords.removeFirst()
                finalCount += words.size
                partialWords = emptyList()
            } else {
                lastPartial = upd.text
                partialWords = words
            }
            val t = tracker ?: return
            val heard = finalCount + partialWords.size
            val before = t.position
            val u = t.onHypothesis(finalWords.toList() + partialWords)
            updates++
            if (u.moved && u.reason != "follow") {
                log(
                    "  %.1f s: %s -> word %d/%d (matched %d, score %.1f)"
                        .format(Locale.ROOT, atSec, u.reason, u.position, t.size, u.matches, u.score),
                )
            }
            // New words were heard, but the position stayed: the highlight is behind the reader for this update.
            if (heard > heardWords && t.position <= before && t.position < t.size) {
                lagged++
                lagRun++
                longestLag = maxOf(longestLag, lagRun)
            } else if (t.position > before) {
                lagRun = 0
            }
            heardWords = maxOf(heardWords, heard)
        }
    }

    private class Wav(val samples: FloatArray, val sampleRate: Int, val channels: Int)

    /** 16-bit PCM WAV (plain or WAVE_FORMAT_EXTENSIBLE), channels averaged to mono. */
    private fun readWav(file: File): Wav {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "${file.name}: not a WAV file" }
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val len = b.getInt(pos + 4)
            val body = pos + 8
            if (tag(pos) == "fmt ") {
                format = b.getShort(body).toInt() and 0xFFFF
                channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
                // WAVE_FORMAT_EXTENSIBLE: the sub-format GUID starts with the real format code.
                if (format == 0xFFFE && len >= 26) format = b.getShort(body + 24).toInt() and 0xFFFF
            } else if (tag(pos) == "data") {
                dataStart = body
                // Streamed recordings may leave the size at 0 or -1: take the rest of the file.
                dataLen = if (len <= 0 || body + len > bytes.size) bytes.size - body else len
                break
            }
            if (len < 0) break
            pos = body + len + (len and 1)
        }
        require(format == 1 && bits == 16 && channels > 0 && rate > 0 && dataStart >= 0) {
            "${file.name}: need 16-bit PCM (format $format, $bits bits, $channels channels, $rate Hz)"
        }
        val frames = dataLen / (2 * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += b.getShort(dataStart + (f * channels + c) * 2)
            out[f] = sum / (channels * 32768f)
        }
        return Wav(out, rate, channels)
    }

    /** A file under the app's filesDir, by name or relative path. */
    private fun fileIn(context: Context, name: String): File {
        val dir = context.filesDir.canonicalFile
        val f = File(dir, name).canonicalFile
        require(f.path.startsWith(dir.path + File.separator)) { "$name: not inside ${dir.path}" }
        require(f.isFile) { "${f.path}: no such file (copy it with adb shell run-as)" }
        return f
    }

    private companion object {
        const val ACTION = "com.olerast.suflyor.BENCH"
        const val TAG = "SuflyorBench"
        const val BROADCAST_BUDGET_MS = 50_000L

        fun log(message: String) {
            Log.i(TAG, message)
        }
    }
}
