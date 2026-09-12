param(
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$linkPath = Join-Path $repoRoot 'alx-preview8-test-link.private.txt'
if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB not found: $Adb" }

$link = (Get-Content -Raw -LiteralPath $linkPath).Trim()
if (-not $link.StartsWith('aetherlink://')) {
    throw 'Unexpected profile scheme'
}

$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($link))
$remoteCommand = 'am start -a android.intent.action.VIEW -d "$(echo ' + $encoded + ' | base64 -d)" -p io.aetherlinkx.client.debug'
& $Adb shell $remoteCommand | Out-Null
Start-Sleep -Seconds 2

& $Adb shell screencap -p /sdcard/alx_preview8_import_fixed.png
& $Adb pull /sdcard/alx_preview8_import_fixed.png (Join-Path $repoRoot 'alx_preview8_import_fixed.png') | Out-Null
