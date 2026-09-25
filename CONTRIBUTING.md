# Contributing

Thanks for wanting to make Suflyor better! Bug reports, testing on your phone, translations and code are all welcome.

## Reporting bugs

Open an issue using the **Bug report** form. The most useful thing you can attach is the app journal: **Настройки → Журнал → Поделиться** (Settings → Journal → Share). It shows what the microphone and the speech recognizer were doing, and the device model and Android version.

Phones differ a lot (Samsung, Xiaomi, Honor…), so "works on my phone / doesn't on mine" reports are valuable too.

## Building

You need JDK 17 and the Android SDK (platform 36). On first build Gradle downloads the speech library (about 50 MB) and the Russian model (about 28 MB) and checks their SHA-256.

```bash
./gradlew assembleDebug testDebugUnitTest
```

On Windows you can also use `.\build.ps1` and `.\install.ps1`. They read `sdk.dir` and `jdk.dir` from `local.properties`.

## Code

- **Language and UI.** Kotlin with Jetpack Compose; the floating window uses plain Views because it lives in the accessibility service.
- **Where logic lives.** Keep the logic that can run on the JVM (importers, text layout, voice tracker) free of Android APIs and cover it with unit tests in `app/src/test`. The tracker has a random stress test; a change to `ScriptTracker` must keep it passing.
- **Style.** Match the surrounding code; comments explain *why*, not *what*.
- **Pull requests.** One topic per pull request. Describe how you tested it and on which phone.

## Languages

The recognizer is sherpa-onnx, which has streaming models for many languages. Adding a language means:
1. a model entry in `fetchSpeechAssets` (with its SHA-256);
2. a config in `SherpaAsr`;
3. stop words in `TextNorm`.

Open an issue first so we can plan it together.

## License

By contributing you agree that your contribution is licensed under the GPL-3.0, like the rest of the project.
