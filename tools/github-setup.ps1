# One-time setup of the GitHub repository after it is created and pushed. Needs the GitHub CLI, logged in
# ("gh auth login"). Run from anywhere:
#   .\tools\github-setup.ps1 -Repo yourname/voice-teleprompter-android
#   .\tools\github-setup.ps1 -Repo yourname/voice-teleprompter-android -SkipSecrets     # only links and settings
#
# What it does:
#   1. replaces the OWNER/suflyor placeholder in README, SECURITY and FUNDING links with the real repository;
#   2. stores the release signing key as Actions secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS,
#      KEY_PASSWORD), read from keystore.properties; the values are never printed;
#   3. sets the description and topics, turns on private vulnerability reporting and immutable releases.
# Commit and push the changed files afterwards.
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [string]$KeystoreProperties = (Join-Path (Split-Path $PSScriptRoot -Parent) 'keystore.properties'),
    [switch]$SkipSecrets
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if ($Repo -notmatch '^[\w.-]+/[\w.-]+$') { throw "Repo must look like owner/name, got '$Repo'" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'Install the GitHub CLI first: https://cli.github.com' }
& gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Log in first: gh auth login' }

# 1. Links
$files = 'README.md', 'README.ru.md', 'SECURITY.md', 'DONATE.md', '.github\FUNDING.yml', '.github\ISSUE_TEMPLATE\config.yml'
$utf8 = New-Object System.Text.UTF8Encoding($false)
foreach ($f in $files) {
    $path = Join-Path $root $f
    if (-not (Test-Path $path)) { continue }
    $text = [IO.File]::ReadAllText($path, $utf8)
    $new = $text.Replace('OWNER/suflyor', $Repo)
    if ($new -ne $text) {
        [IO.File]::WriteAllText($path, $new, $utf8)
        Write-Host "links updated: $f"
    }
}

# 2. Signing secrets
if (-not $SkipSecrets) {
    if (-not (Test-Path $KeystoreProperties)) { throw "No $KeystoreProperties (storeFile, storePassword, keyAlias, keyPassword)" }
    $props = @{}
    foreach ($line in Get-Content $KeystoreProperties -Encoding UTF8) {
        if ($line -match '^\s*([^#!=\s]+)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2] -replace '\\(.)', '$1' }
    }
    foreach ($k in 'storeFile', 'storePassword', 'keyAlias', 'keyPassword') {
        if (-not $props[$k]) { throw "keystore.properties has no $k" }
    }
    $jks = $props['storeFile']
    if (-not [IO.Path]::IsPathRooted($jks)) { $jks = Join-Path $root $jks }
    $secrets = [ordered]@{
        KEYSTORE_BASE64   = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
        KEYSTORE_PASSWORD = $props['storePassword']
        KEY_ALIAS         = $props['keyAlias']
        KEY_PASSWORD      = $props['keyPassword']
    }
    # Values go through stdin: Windows PowerShell 5.1 mangles quotes and trailing backslashes in native arguments,
    # and nothing shows up in the process list. gh drops the trailing newline PowerShell adds.
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    foreach ($name in $secrets.Keys) {
        $secrets[$name] | & gh secret set $name --repo $Repo --app actions | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not set secret $name" }
        Write-Host "secret set: $name"
    }
}

# 3. Repository settings (best effort: some need admin rights or a newer API).
# A failing gh writes to stderr; with ErrorActionPreference=Stop that would end the script before the hints below.
$ErrorActionPreference = 'Continue'
& gh repo edit $Repo `
    --description 'Voice-following teleprompter for Android: floats over Instagram, TikTok and the camera. Offline, no internet permission.' `
    --add-topic teleprompter --add-topic android --add-topic speech-recognition --add-topic offline `
    --add-topic kotlin --add-topic jetpack-compose --add-topic sherpa-onnx --add-topic content-creation | Out-Null
if ($LASTEXITCODE -eq 0) { Write-Host 'description and topics: set' }
else { Write-Host 'description and topics: could not set them, add by hand on the repository page' -ForegroundColor Yellow }
& gh api -X PUT "repos/$Repo/private-vulnerability-reporting" --silent 2>$null
if ($LASTEXITCODE -eq 0) { Write-Host 'private vulnerability reporting: on' }
else { Write-Host 'private vulnerability reporting: turn on by hand in Settings -> Security' -ForegroundColor Yellow }
& gh api -X PUT "repos/$Repo/immutable-releases" --silent 2>$null
if ($LASTEXITCODE -eq 0) { Write-Host 'immutable releases: on' }
else { Write-Host 'immutable releases: turn on by hand in Settings -> General -> Releases' -ForegroundColor Yellow }

Write-Host "`nDone. Commit the updated links, then publish a release as described in RELEASING.md."
