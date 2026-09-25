# Releasing

How the maintainer publishes Suflyor. Users don't need any of this.

## One-time setup

1. **Back up the signing key.** The key is `suflyor-release.jks` plus `keystore.properties` with its passwords, stored outside the repository, in a separate folder on your computer. Keep two copies away from this computer: a password manager and an offline drive. **If the key is lost, installed apps can never be updated**. Users would have to uninstall and lose their scripts. The same key is needed for Google developer verification.
2. **Hide your email in commits.** In GitHub → Settings → Emails, turn on *Keep my email addresses private* and copy the `…@users.noreply.github.com` address. Then run:
   ```bash
   git config user.email "ID+USERNAME@users.noreply.github.com"
   # Rewrites the author of every local commit. Do this only before the first push.
   git rebase --root --exec "git commit --amend --reset-author --no-edit"
   ```
3. **Take the screenshots** (see [Screenshots](#screenshots)): README.md shows `docs/images/screens.png`, README.ru.md shows `docs/images/screens-ru.png`.
4. **Fill in the donation links** in `DONATE.md` and `.github/FUNDING.yml` (see [Donations](#donations)): links, and wallet addresses if you use crypto, or delete the rows you don't need. Until no placeholder is left, release notes leave out the donate line.
5. **Create the repository** on GitHub as **private** and empty: no README, license or .gitignore, because they are already here. Then push:
   ```bash
   git remote add origin https://github.com/USERNAME/suflyor.git
   git push -u origin main
   ```
6. **Run the setup script.** It fills in the repository links, stores the signing key as Actions secrets and sets the description and topics:
   ```powershell
   .\tools\github-setup.ps1 -Repo USERNAME/suflyor
   git add -A ; git commit -m "Point links at the repository" ; git push
   ```
   In **Settings → Actions → General**, set *Workflow permissions* to read-only; the release workflow asks for write access itself.
7. **Check the README on GitHub, then make the repository public:** **Settings → General → Danger Zone → Change visibility**. Run the script once more, `.\tools\github-setup.ps1 -Repo USERNAME/suflyor -SkipSecrets`: private vulnerability reporting and immutable releases can only be switched on for a public repository. Check in **Settings → General → Releases** that *immutable releases* is on. Push the first release tag only after this: build attestations need a public repository.
8. **Upload the banner as the social preview.** Go to **Settings → General → Social preview** and upload `docs/images/banner.png`.

## Each release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName`, for example `0.5`.
2. Add a `## 0.5 — YYYY-MM-DD` section at the top of `CHANGELOG.md`. It becomes the release notes. Write the short store version too: `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and `ru-RU/changelogs/<versionCode>.txt`, at most 500 characters each.
3. Run `.\build.ps1` and check the app on the phone with `.\install.ps1`.
4. Commit everything (the new changelog files too), then tag and push:
   ```bash
   git add -A
   git commit -m "Release 0.5"
   git tag v0.5
   git push origin main v0.5
   ```
5. The [Release workflow](.github/workflows/release.yml) then:
   - checks that the tag matches `versionName`;
   - runs the tests;
   - builds and signs the APK;
   - checks the signing certificate and that there is no `INTERNET` permission;
   - writes `SHA256SUMS.txt` and a build provenance attestation;
   - publishes the GitHub release.

   Obtainium users get the update automatically.

If the workflow fails, fix the problem, delete the tag (`git push --delete origin v0.5`, `git tag -d v0.5`) and tag again. Once a release is published with immutable releases on, its tag can't be reused: bump the version instead.

### Building a signed APK locally

`.\build.ps1 -Release` signs with the key named in `keystore.properties` in the project root, which git ignores. Without that file the release APK comes out unsigned. Check the result:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The certificate SHA-256 must be `77:F3:94:32:F5:05:FB:B7:8B:85:E7:37:CD:00:21:0A:44:C0:7A:13:2C:AC:FF:81:FE:9D:9D:79:CD:E9:A9:06`.

## Screenshots

With the phone connected over USB:

```powershell
.\build.ps1
.\tools\capture-screenshots.ps1                          # English: library, script, editor, settings
.\tools\capture-screenshots.ps1 -Locale ru               # the same in Russian
.\tools\capture-screenshots.ps1 -Locale en -Manual 5-overlay   # anything on screen now, e.g. the prompter over the camera
python docs\tools\render_screens.py en                   # docs/images/screens.png for README.md
python docs\tools\render_screens.py ru                   # docs/images/screens-ru.png for README.ru.md
```

The status and navigation bars are cropped, so notifications never end up in the pictures. Allow the microphone and turn on the accessibility service of the debug build ("Suflyor dev") first, so the screens show no setup warnings; the script keeps those permissions when it resets the app's data.

## Donations

Choose the channels that work where your bank account is, then fill them into `DONATE.md` and `.github/FUNDING.yml`. Remove the rows you don't use.

- **Only Russian cards:** Boosty (main), CloudTips (quick tips), optionally crypto addresses in `DONATE.md`.
- **Bank account in a country Stripe supports:** GitHub Sponsors (0% fee), Ko-fi (one-off tips), Liberapay, plus Boosty for the Russian audience.

Mention the donate link in each release note (the workflow adds it) and in videos about the app.

## Distribution beyond GitHub

- **Obtainium:** works out of the box. It reads GitHub Releases, and the README has the badge.
- **Google developer verification:** in 2026 it covers only installs from seven app stores in Brazil, Indonesia, Singapore and Thailand; GitHub and Obtainium installs are not checked. From 2027 it applies worldwide on phones with Google services. To spare users the "advanced flow", register in the [Android Developer Console](https://developer.android.com/developer-verification) with full verification (government ID, proof of address in your country of residence, a one-time $25 card payment), then register the package `com.olerast.suflyor` with this key's SHA-256; Google checks key ownership with a small APK signed by it. Use your real country and address: the fee is not refunded if the data is wrong. Until then the README explains the advanced flow.
- **IzzyOnDroid:** takes the APK from GitHub Releases. Its policy rejects apps whose code is largely AI-generated, and the README states honestly how this app was made. Each file is also limited to 30 MB.
- **F-Droid:** builds from source. The prebuilt sherpa-onnx AAR would have to be replaced by a source build of sherpa-onnx.
