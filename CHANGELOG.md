# Changelog

All notable changes to Suflyor. Versions follow `versionName` in `app/build.gradle.kts`; each release's notes are taken from its section here.

## 0.3 — 2026-09-25

First public version.

- **Floating prompter over any app** — Instagram, TikTok, the camera:
  - it follows your voice while that app records video;
  - the text starts right under the front camera;
  - lock mode lets touches pass through to the camera buttons;
  - for landscape recording the text turns towards the lens.
- **Voice following**, offline Russian speech recognition (sherpa-onnx):
  - searches the whole script, and rare words count more than common ones;
  - finds a neighbouring line after about two words, a far jump after three or four;
  - stays put in pauses and when you talk off script.
- **Quick Settings tile** starts the prompter over whatever app is open.
- **Remote controls**: volume keys, Bluetooth selfie remotes, rings, clickers and keyboards. Keys can be reassigned.
- **Script library**:
  - import of TXT (UTF-8, UTF-16, Windows-1251, KOI8-R), Markdown and Obsidian, DOCX, ODT, RTF, HTML and PDF;
  - share-to and open-with from other apps;
  - a simple editor with `**emphasis**`, `# headings` and `[notes that are not read]`.
- **Rehearsal** mode and timed scrolling with a 3-2-1 countdown.
- **Private by design**:
  - no `INTERNET` permission, analytics or ads;
  - the accessibility service works only during a session and can't read the screen;
  - scripts are excluded from cloud backup, and release builds don't copy the journal to the system log.
- **Hardened importers**: limits against zip bombs, XML entity tricks and oversized files; DOCX tracked changes and Strict OOXML; RTF font code pages.
- **Signed releases** built by GitHub Actions, with SHA-256 checksums and build provenance attestations.
