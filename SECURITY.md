# Security policy

Suflyor asks for sensitive access — the microphone, an accessibility service and drawing over other apps — so security reports are taken seriously.

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private reporting instead: **Security → Report a vulnerability** on this repository.

Include what you found, how to reproduce it (device, Android version, app version) and what an attacker could gain. You will get an answer within a week.

## Scope

In scope:
- the app's code in this repository;
- the release APKs published in GitHub Releases;
- the build and release pipeline (`.github/workflows`, the Gradle build, including the download of the speech library and model).

Out of scope: vulnerabilities in Android itself or in third-party apps (report those to their vendors), and attacks that need a phone that is already rooted or compromised.

## Verifying a download

Release APKs are built by GitHub Actions from a tagged commit. Each APK has a SHA-256 checksum file and a build provenance attestation. APKs are signed with this certificate:

```
SHA-256: 77:F3:94:32:F5:05:FB:B7:8B:85:E7:37:CD:00:21:0A:44:C0:7A:13:2C:AC:FF:81:FE:9D:9D:79:CD:E9:A9:06
```

To check a file:

```bash
apksigner verify --print-certs Suflyor-*.apk
gh attestation verify Suflyor-*.apk --repo olerast67/suflyor
```
