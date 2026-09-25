# Installs the built APK on a phone connected over USB ("USB debugging" on) and shows the app journal live
# (debug builds only: release builds don't write to logcat).
#   .\install.ps1            debug build
#   .\install.ps1 -Release   release build
# adb comes from sdk.dir in local.properties. Stop the journal with Ctrl+C.
param([switch]$Release)

$file = Join-Path $PSScriptRoot 'local.properties'
$sdk = (Get-Content $file | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1) -replace '^\s*sdk\.dir\s*=\s*', '' -replace '\\(.)', '$1'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$apk = if ($Release) { 'app\build\outputs\apk\release\app-release.apk' } else { 'app\build\outputs\apk\debug\app-debug.apk' }

# The debug build has its own package id, so it installs next to the release build.
$package = if ($Release) { 'com.olerast.suflyor' } else { 'com.olerast.suflyor.debug' }

& $adb install -r (Join-Path $PSScriptRoot $apk)
if ($LASTEXITCODE -eq 0) {
    & $adb shell am start -n "$package/com.olerast.suflyor.MainActivity" | Out-Null
    & $adb logcat -c
    & $adb logcat -s Suflyor:I
}
