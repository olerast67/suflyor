package com.olerast.suflyor.speech

/** Streaming speech recognizer: feed audio, get the current utterance text; [AsrUpdate.isFinal] ends the utterance. */
interface AsrEngine {
    val description: String

    /** Called on the audio thread. Returns an update only when the recognized text changed or an utterance ended. */
    fun accept(samples: FloatArray, sampleRate: Int): AsrUpdate?

    /** Words of the current script to bias recognition towards (may be ignored by the engine). */
    fun setBiasWords(words: List<String>)

    fun reset()

    fun release()
}

data class AsrUpdate(val text: String, val isFinal: Boolean)
