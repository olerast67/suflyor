# Builds the app and runs the tests.
#   .\build.ps1            debug APK:   app\build\outputs\apk\debug\app-debug.apk
#   .\build.ps1 -Release   release APK: app\build\outputs\apk\release\app-release.apk
# JDK 17 comes from JAVA_HOME, or from jdk.dir in local.properties (next to sdk.dir).
param([switch]$Release)

function Get-LocalProperty([string]$name) {
    $file = Join-Path $PSScriptRoot 'local.properties'
    if (-not (Test-Path $file)) { return $null }
    $line = Get-Content $file | Where-Object { $_ -match "^\s*$name\s*=" } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -replace "^\s*$name\s*=\s*", '') -replace '\\(.)', '$1'
}

if (-not $env:JAVA_HOME) {
    $jdk = Get-LocalProperty 'jdk.dir'
    if ($jdk) { $env:JAVA_HOME = $jdk }
}
$task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
& "$PSScriptRoot\gradlew.bat" --console=plain $task testDebugUnitTest
