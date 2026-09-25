# Third-party components

Suflyor is licensed under the GNU GPL v3.0 (see [LICENSE](LICENSE)). It includes the components below under their own licenses. All of them are compatible with GPL-3.0.

## Shipped inside the APK

| Component | Used for | License | Source |
|---|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 (Android AAR) | Streaming speech recognition on the device | Apache-2.0 | [v1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (`libonnxruntime.so`, from the AAR) | Runs the neural network | MIT | https://github.com/microsoft/onnxruntime |
| [vosk-model-small-streaming-ru](https://huggingface.co/alphacep/vosk-model-small-streaming-ru), int8 export by sherpa-onnx | Russian speech model | Apache-2.0 | [model](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16) |
| AndroidX: Jetpack Compose, Activity, Core | User interface | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin standard library | Runtime | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | Runtime used by Compose | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Material Design icons (vector paths) | Icons | Apache-2.0 | https://github.com/google/material-design-icons |

### Linked into `libsherpa-onnx-jni.so` by the sherpa-onnx build

The prebuilt sherpa-onnx library also contains code the app does not call (text-to-speech, speaker diarization). It comes from these projects:

| Component | License | Source |
|---|---|---|
| [espeak-ng](https://github.com/k2-fsa/espeak-ng) (fork used by sherpa-onnx for text-to-speech) | GPL-3.0-or-later | https://github.com/k2-fsa/espeak-ng |
| [piper-phonemize](https://github.com/csukuangfj/piper-phonemize) | MIT | https://github.com/csukuangfj/piper-phonemize |
| [OpenFst](https://www.openfst.org/) | Apache-2.0 | https://www.openfst.org/ |
| [kaldifst](https://github.com/k2-fsa/kaldifst), [kaldi-decoder](https://github.com/k2-fsa/kaldi-decoder), [kaldi-native-fbank](https://github.com/csukuangfj/kaldi-native-fbank) | Apache-2.0 | https://github.com/k2-fsa |
| [simple-sentencepiece](https://github.com/pkufool/simple-sentencepiece) | Apache-2.0 | https://github.com/pkufool/simple-sentencepiece |
| [nlohmann/json](https://github.com/nlohmann/json) | MIT | https://github.com/nlohmann/json |

The corresponding source for these binaries is the sherpa-onnx [v1.13.8 tag](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) together with the dependency versions its CMake files pin. If you need a copy of that source and cannot get it from there, open an issue and it will be provided.

## Not stored in this repository

The speech library and model are not committed. The `fetchSpeechAssets` Gradle task downloads them from the sources above, from pinned revisions, and checks every file against a pinned SHA-256 (see `app/build.gradle.kts`).

License texts: [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) · [MIT](https://opensource.org/license/mit) · [GPL-3.0](LICENSE)
