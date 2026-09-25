# Contributing

Thanks for wanting to make Suflyor better! Bug reports, testing on your phone, translations and code are all welcome.

## Reporting bugs

Open an issue using the **Bug report** form. The most useful thing you can attach is the app log: **Settings → Log → Share** (Настройки → Журнал → Поделиться). It shows what the microphone and the speech recognizer were doing, and the device model and Android version.

Phones differ a lot (Samsung, Xiaomi, Honor…), so "works on my phone / doesn't on mine" reports are valuable too.

## Building

You need JDK 17 and the Android SDK (platform 36). On first build Gradle downloads the speech library (about 50 MB) and the Russian and English models (about 28 and 73 MB) and checks their SHA-256.

```bash
./gradlew assembleDebug testDebugUnitTest
```

On Windows you can also use `.\build.ps1` and `.\install.ps1`. They read `sdk.dir` and `jdk.dir` from `local.properties`.

## Code

- **Language and UI.** Kotlin with Jetpack Compose; the floating window uses plain Views because it lives in the accessibility service.
- **Where logic lives.** Keep the logic that can run on the JVM (importers, text layout, voice tracker) free of Android APIs and cover it with unit tests in `app/src/test`. The tracker has a random stress test; a change to `ScriptTracker` must keep it passing.
- **Style.** Match the surrounding code; comments explain *why*, not *what*.
- **No emojis** in the app's text, the docs, the website, commit messages or release notes. Where a picture helps, use an icon (a vector drawable in the app, an inline SVG on the website).
- **Pull requests.** One topic per pull request. Describe how you tested it and on which phone.

## Languages

The recognizer is sherpa-onnx, which has streaming models for many languages. English and Russian show the pattern for adding one:

1. **Model.** Add its files to `fetchSpeechAssets` in `app/build.gradle.kts` (pinned revision and SHA-256) under `assets/asr-<code>/`, and its config to `SherpaAsr.create`. Check the license allows redistribution in a GPL-3.0 app and add it to THIRD_PARTY_NOTICES.md.
2. **Speech rules.** Add an entry to `SpeechLang` and a normalizer like `script/EnglishNorm.kt`: how words are split and normalized, stop words, fillers, word weights, stems and similarity, abbreviations. Russian (`TextNorm`) must stay byte-identical: `RussianGoldenTest` guards it.
3. **Tests.** A tracker test like `EnglishTrackerTest` and a stress test in `TrackerStressTest.kt` with the language's own recognizer noise.
4. **Interface** (optional, separately): `values-<code>/strings*.xml` and the language in `res/xml/locales_config.xml` and `localeFilters`.

Open an issue first so we can plan it together.

## License

By contributing you agree that your contribution is licensed under the GPL-3.0, like the rest of the project.
