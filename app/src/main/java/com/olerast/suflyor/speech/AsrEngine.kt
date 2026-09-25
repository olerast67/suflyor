package com.olerast.suflyor.speech

import com.olerast.suflyor.script.SpeechLang

/** Streaming speech recognizer: feed audio, get the current utterance text; [AsrUpdate.isFinal] ends the utterance. */
interface AsrEngine {
    val description: String

    /** Language of the model: its text is normalized with [SpeechLang.words], and a script in another one needs another engine. */
    val lang: SpeechLang

    /** Called on the audio thread. Returns an update only when the recognized text changed or an utterance ended. */
    fun accept(samples: FloatArray, sampleRate: Int): AsrUpdate?

    /** Words of the current script to bias recognition towards (may be ignored by the engine). */
    fun setBiasWords(words: List<String>)

    fun reset()

    /** Frees the model. Safe to call twice; afterwards [accept] returns null and the other calls do nothing. */
    fun release()
}

data class AsrUpdate(val text: String, val isFinal: Boolean)
