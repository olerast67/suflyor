package com.olerast.suflyor.session

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import com.olerast.suflyor.speech.AudioCapture

/**
 * Watches every active microphone capture on the device. Other apps' entries are anonymized by Android,
 * but their audio source and "silenced" flag are visible — exactly what the test needs:
 * which source Instagram / TikTok / the camera use, and whether anyone is being silenced.
 */
class RecordingMonitor(
    context: Context,
    private val ourSessionId: () -> Int,
    private val onChange: (List<Rec>) -> Unit,
) {
    data class Rec(
        val ours: Boolean,
        val source: Int,
        val silenced: Boolean,
        val sampleRate: Int,
        val channels: Int,
        val device: String?,
    ) {
        fun describe(): String {
            val who = if (ours) "мы" else "другое приложение"
            val mute = if (silenced) "ЗАГЛУШЕНО" else "слышит"
            return "$who: ${AudioCapture.sourceName(source)}, $sampleRate Гц, ${channels}ch, $mute" +
                (device?.let { ", $it" } ?: "")
        }
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = publish(configs)
    }
    private var registered = false

    fun start() {
        if (registered) return
        audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        registered = true
        publish(audioManager.activeRecordingConfigurations)
    }

    fun stop() {
        if (!registered) return
        audioManager.unregisterAudioRecordingCallback(callback)
        registered = false
    }

    fun snapshot(): List<Rec> = convert(audioManager.activeRecordingConfigurations)

    private fun publish(configs: List<AudioRecordingConfiguration>) = onChange(convert(configs))

    private fun convert(configs: List<AudioRecordingConfiguration>): List<Rec> {
        val sid = ourSessionId()
        return configs.map { c ->
            Rec(
                ours = sid != 0 && c.clientAudioSessionId == sid,
                source = c.clientAudioSource,
                silenced = c.isClientSilenced,
                sampleRate = c.clientFormat.sampleRate,
                channels = c.clientFormat.channelCount,
                device = c.audioDevice?.let { deviceName(it) },
            )
        }
    }

    private fun deviceName(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "встроенный микрофон"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "проводная гарнитура"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            else -> "тип ${d.type}"
        }
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
        return if (name != null) "$type ($name)" else type
    }
}
