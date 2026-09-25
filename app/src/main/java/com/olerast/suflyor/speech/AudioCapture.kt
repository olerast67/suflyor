package com.olerast.suflyor.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Microphone capture on its own thread. Never uses CAMCORDER / VOICE_COMMUNICATION and never marks the capture
 * as privacy-sensitive: such a capture would take the microphone away from the camera app that is recording.
 */
class AudioCapture(
    private val source: Int,
    private val sampleRate: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the capture thread with ~100 ms of mono audio in [-1, 1]. */
        fun onAudio(samples: FloatArray, sampleRate: Int, rmsDb: Float, digitalSilence: Boolean)
        fun onCaptureError(message: String)
    }

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val audioSessionId: Int get() = record?.audioSessionId ?: 0

    /** Whether Android currently feeds this capture with silence because another app has priority (API 29+). */
    val isSilencedBySystem: Boolean?
        get() = runCatching { record?.activeRecordingConfiguration?.isClientSilenced }.getOrNull()

    @SuppressLint("MissingPermission")
    fun start(): String? {
        require(source != MediaRecorder.AudioSource.CAMCORDER && source != MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "Частота $sampleRate Гц не поддерживается микрофоном"
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val builder = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf * 4, sampleRate * 2))
        if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false)
        val rec = try {
            builder.build()
        } catch (e: Exception) {
            return "AudioRecord не создан: ${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "AudioRecord не инициализирован (нет разрешения на микрофон?)"
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            return "Не удалось начать запись: ${e.message}"
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return "Система не дала начать запись — микрофон занят другим приложением"
        }
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "audio-capture").also { it.start() }
        return null
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = sampleRate / 10
        val shorts = ShortArray(chunk)
        while (running) {
            val n = rec.read(shorts, 0, chunk)
            if (n < 0) {
                if (running) listener.onCaptureError("Ошибка чтения микрофона: $n")
                break
            }
            if (n == 0) continue
            val samples = FloatArray(n)
            var sum = 0.0
            var nonZero = false
            for (i in 0 until n) {
                val s = shorts[i]
                if (s.toInt() != 0) nonZero = true
                val f = s / 32768f
                samples[i] = f
                sum += f * f
            }
            val rms = sqrt(sum / n)
            val db = if (rms > 1e-7) (20 * log10(rms)).toFloat() else -120f
            listener.onAudio(samples, sampleRate, db, !nonZero)
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        thread?.join(700)
        thread = null
        runCatching { record?.release() }
        record = null
    }

    companion object {
        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
            1997 -> "RADIO_TUNER"
            1998 -> "HOTWORD"
            1999 -> "ECHO_REFERENCE"
            else -> "source $source"
        }
    }
}
