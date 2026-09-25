# Takes the README screenshots from a phone connected over USB ("USB debugging" on), in English or Russian.
#   .\tools\capture-screenshots.ps1                 English: library, script, editor, settings -> docs\images\screens\en\
#   .\tools\capture-screenshots.ps1 -Locale ru      the same in Russian -> docs\images\screens\ru\
#   .\tools\capture-screenshots.ps1 -Locale en -Manual 5-overlay
#                                                   saves whatever is on the screen now (e.g. the prompter over the camera)
# Uses the debug build (package com.olerast.suflyor.debug). Its scripts and settings are wiped through run-as, which
# keeps the permissions and the accessibility service you granted, so only the built-in sample in that language shows.
# The app language is set per app (Android 13+). Status and navigation bars are cut off, so notifications and the clock
# never end up in the pictures. Build first: .\build.ps1; afterwards: python docs\tools\render_screens.py <locale>
param(
    [ValidateSet('en', 'ru')][string]$Locale = 'en',
    [string]$Manual
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$props = Get-Content (Join-Path $root 'local.properties')
$sdk = (($props | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1) -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\(.)', '$1'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$package = 'com.olerast.suflyor.debug'
$out = Join-Path $root "docs\images\screens\$Locale"
New-Item -ItemType Directory -Force $out | Out-Null
$store = Join-Path $root ("fastlane\metadata\android\{0}\images\phoneScreenshots" -f @{ en = 'en-US'; ru = 'ru-RU' }[$Locale])
New-Item -ItemType Directory -Force $store | Out-Null
$tmp = Join-Path ([IO.Path]::GetTempPath()) 'suflyor-shots'
New-Item -ItemType Directory -Force $tmp | Out-Null

# What the script taps, as the app shows it in each language.
$labels = @{
    en = @{ Sample = 'Sample'; Edit = 'Edit'; Settings = 'Settings' }
    ru = @{ Sample = 'Пример'; Edit = 'Редактировать'; Settings = 'Настройки' }
}[$Locale]

# adb writes progress ("1 file pulled") to stderr; Windows PowerShell 5.1 would turn that into a terminating error
# under ErrorActionPreference=Stop, so stderr is collected here and only the exit code decides.
function Adb {
    $ErrorActionPreference = 'Continue'
    $out = & $adb @args 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "adb $args failed: $out" }
    $out
}

# Status and navigation bar bands, from the window manager's insets (fallback: 3% of the height each).
function Get-Bars {
    $size = (& $adb shell wm size | Select-String 'Physical size: (\d+)x(\d+)').Matches[0].Groups
    $h = [int]$size[2].Value
    $dump = (& $adb shell dumpsys window) -join "`n"
    $top = [regex]::Match($dump, 'type=statusBars frame=\[0,0\]\[\d+,(\d+)\]')
    $bottom = [regex]::Match($dump, 'type=navigationBars frame=\[0,(\d+)\]\[\d+,\d+\]')
    [pscustomobject]@{
        Top = if ($top.Success) { [int]$top.Groups[1].Value } else { [int]($h * 0.03) }
        Bottom = if ($bottom.Success) { $h - [int]$bottom.Groups[1].Value } else { [int]($h * 0.03) }
    }
}

function Save-Screen([string]$name) {
    Start-Sleep -Milliseconds 900
    $raw = Join-Path $tmp "$name.png"
    Adb shell screencap -p /sdcard/suflyor-shot.png
    Adb pull /sdcard/suflyor-shot.png $raw | Out-Null
    Adb shell rm /sdcard/suflyor-shot.png
    $bars = Get-Bars
    python (Join-Path $root 'docs\tools\crop_screen.py') $raw (Join-Path $out "$name.png") $bars.Top $bars.Bottom
    if ($LASTEXITCODE -ne 0) { throw "Could not crop $name.png (python with Pillow is needed)" }
    # Store listings (IzzyOnDroid, F-Droid) read the same pictures from the fastlane folder.
    Copy-Item (Join-Path $out "$name.png") $store -Force
    Write-Host "saved $Locale/$name.png"
}

# Taps the first element whose text or description matches. uiautomator dumps rebind enabled accessibility
# services; that is harmless here because no prompter session runs while the screenshots are taken.
function Tap([string]$label) {
    Adb shell uiautomator dump /sdcard/suflyor-ui.xml | Out-Null
    $xml = Join-Path $tmp 'ui.xml'
    Adb pull /sdcard/suflyor-ui.xml $xml | Out-Null
    Adb shell rm /sdcard/suflyor-ui.xml
    [xml]$ui = Get-Content $xml -Encoding UTF8
    $node = $ui.SelectNodes('//node') | Where-Object { $_.text -eq $label -or $_.'content-desc' -eq $label } | Select-Object -First 1
    if (-not $node) { throw "No '$label' on the screen" }
    $b = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    Adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
}

# A locked or sleeping phone would give pictures of the lock screen.
function Assert-Unlocked {
    $power = (& $adb shell dumpsys power) -join "`n"
    $window = (& $adb shell dumpsys window) -join "`n"
    if ($power -notmatch 'mWakefulness=Awake' -or $window -match 'isKeyguardShowing=true') {
        throw 'Unlock the phone and keep the screen on (Developer options -> Stay awake keeps it on while charging).'
    }
}

# The app is restarted by ending its process, not with force-stop: Android keeps a force-stopped app's accessibility
# service unbound, but brings a killed one back by itself. The debug build mirrors its journal to logcat, so the
# app's own "connected" line tells when the service (and with it the readiness shown on screen) is back.
function Restart-App {
    Adb logcat -c | Out-Null
    $procId = ((& $adb shell pidof $package) -join '').Trim()
    if ($procId) { Adb shell run-as $package kill -9 $procId | Out-Null }
    Start-Sleep -Seconds 1
    Wait-Service
    Adb shell am start -W -n "$package/com.olerast.suflyor.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
}

function Wait-Service {
    $enabled = (& $adb shell settings get secure enabled_accessibility_services) -join ''
    if ($enabled -notmatch [regex]::Escape("$package/")) { return }
    for ($i = 0; $i -lt 60; $i++) {
        $log = (& $adb logcat -d -s Suflyor:I) -join "`n"
        if ($log -match 'Accessibility service connected') { return }
        Start-Sleep -Milliseconds 500
    }
    Write-Host 'The accessibility service did not come back; the screens may show it as off.'
}

Assert-Unlocked

if ($Manual) {
    Save-Screen $Manual
    return
}

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw 'Build the debug APK first: .\build.ps1' }
Adb install -r $apk | Out-Null
Adb shell cmd locale set-app-locales $package --user 0 --locales $Locale | Out-Null
# Fresh scripts and settings, but granted permissions stay (pm clear would revoke them).
Adb shell run-as $package rm -rf files/scripts files/script.json shared_prefs | Out-Null
Restart-App

Save-Screen '1-library'
Tap $labels.Sample
Save-Screen '2-script'
Tap $labels.Edit
Save-Screen '3-editor'
# Editor -> script -> library.
Adb shell input keyevent KEYCODE_BACK | Out-Null
Start-Sleep -Milliseconds 700
Adb shell input keyevent KEYCODE_BACK | Out-Null
Start-Sleep -Milliseconds 700
Assert-Unlocked
Tap $labels.Settings
Save-Screen '4-settings'
Write-Host "Done. Pictures are in $out; run: python docs\tools\render_screens.py $Locale"
