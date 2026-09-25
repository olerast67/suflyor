# Installs the built APK on a phone connected over USB ("USB debugging" on) and shows the app journal live.
#   .\install.ps1            debug build
#   .\install.ps1 -Release   release build
# adb comes from sdk.dir in local.properties. Stop the journal with Ctrl+C.
param([switch]$Release)

$file = Join-Path $PSScriptRoot 'local.properties'
$sdk = (Get-Content $file | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1) -replace '^\s*sdk\.dir\s*=\s*', '' -replace '\\(.)', '$1'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$apk = if ($Release) { 'app\build\outputs\apk\release\app-release.apk' } else { 'app\build\outputs\apk\debug\app-debug.apk' }

& $adb install -r (Join-Path $PSScriptRoot $apk)
if ($LASTEXITCODE -eq 0) {
    & $adb shell am start -n com.olerast.suflyor/.MainActivity | Out-Null
    & $adb logcat -c
    & $adb logcat -s Suflyor:I
}
