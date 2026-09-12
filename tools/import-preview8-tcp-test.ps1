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

# Point the primary QUIC connection at a closed test port while preserving the
# real TCP fallback port. This validates fallback without changing the server.
$tcpTestLink = [regex]::Replace(
    $link,
    '^(aetherlink://[^@]+@[^:/?#]+:)\d+',
    { param($match) $match.Groups[1].Value + '444' }
)
$tcpTestLink = [regex]::Replace(
    $tcpTestLink,
    '#.*$',
    '#ALX%20P8%20TCP%20Fallback%20Test'
)

$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($tcpTestLink))
$remoteCommand = 'am start -a android.intent.action.VIEW -d "$(echo ' + $encoded + ' | base64 -d)" -p io.aetherlinkx.client.debug'
& $Adb shell $remoteCommand | Out-Null
Start-Sleep -Seconds 2

Write-Output 'Temporary TCP-fallback profile imported.'
