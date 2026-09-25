package com.olerast.suflyor.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.olerast.suflyor.App

/**
 * Offline streaming Russian recognizer: sherpa-onnx + alphacep's small streaming Zipformer (int8, ~28 MB, in assets).
 * With "hotwords" on, words of the script are boosted through modified beam search.
 */
class SherpaAsr private constructor(
    private val recognizer: OnlineRecognizer,
    private val hotwordsEnabled: Boolean,
    override val description: String,
) : AsrEngine {
    private var stream: OnlineStream = recognizer.createStream()
    private var hotwords = ""
    private var hotwordsChanged = false
    private var lastText = ""

    @Synchronized
    override fun accept(samples: FloatArray, sampleRate: Int): AsrUpdate? {
        if (hotwordsChanged) recreateStream()
        stream.acceptWaveform(samples, sampleRate)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        if (recognizer.isEndpoint(stream)) {
            recognizer.reset(stream)
            lastText = ""
            return if (text.isNotEmpty()) AsrUpdate(text, isFinal = true) else null
        }
        if (text == lastText) return null
        lastText = text
        return AsrUpdate(text, isFinal = false)
    }

    @Synchronized
    override fun setBiasWords(words: List<String>) {
        if (!hotwordsEnabled) return
        // The model's BPE vocabulary is lowercase; keep plain Cyrillic words so every hotword can be encoded.
        hotwords = words.asSequence()
            .map { it.lowercase() }
            .filter { w -> w.length >= 4 && w.all { it in 'а'..'я' || it == 'ё' } }
            .distinct()
            .take(1500)
            .joinToString("/")
        hotwordsChanged = true
    }

    private fun recreateStream() {
        stream.release()
        stream = recognizer.createStream(hotwords)
        hotwordsChanged = false
        lastText = ""
    }

    @Synchronized
    override fun reset() {
        recognizer.reset(stream)
        lastText = ""
    }

    @Synchronized
    override fun release() {
        stream.release()
        recognizer.release()
    }

    companion object {
        private const val DIR = "asr-ru"

        fun create(context: Context): SherpaAsr {
            val hot = App.instance.settings.useHotwords
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$DIR/encoder.int8.onnx",
                        decoder = "$DIR/decoder.onnx",
                        joiner = "$DIR/joiner.int8.onnx",
                    ),
                    tokens = "$DIR/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer2",
                    modelingUnit = if (hot) "bpe" else "",
                    bpeVocab = if (hot) "$DIR/bpe.vocab" else "",
                ),
                // Close an utterance after 0.8 s of silence following speech; the tracker keeps its own history anyway.
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.0f, 0.0f),
                    rule2 = EndpointRule(true, 0.8f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 12.0f),
                ),
                enableEndpoint = true,
                decodingMethod = if (hot) "modified_beam_search" else "greedy_search",
                maxActivePaths = 4,
                hotwordsScore = 1.5f,
            )
            val recognizer = OnlineRecognizer(assetManager = context.assets, config = config)
            val desc = "sherpa-onnx, Zipformer ru small int8, ${config.decodingMethod}" + if (hot) ", слова сценария" else ""
            return SherpaAsr(recognizer, hot, desc)
        }
    }
}
