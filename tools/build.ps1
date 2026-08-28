[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$XrayRoot,
    [string]$GoExecutable = 'go',
    [string]$Output,
    [switch]$SkipScenarios
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $XrayRoot).Path
if (-not $Output) {
    $Output = Join-Path $resolvedRoot 'xray-aetherlinkx.exe'
}

function Invoke-Go([string[]]$Arguments) {
    & $GoExecutable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Go command failed: go $($Arguments -join ' ')"
    }
}

Push-Location $resolvedRoot
try {
    Invoke-Go @('test', './proxy/aetherlinkx', '-count=1')
    Invoke-Go @('test', './infra/conf', '-run', 'AetherLinkX', '-count=1')
    if (-not $SkipScenarios) {
        Invoke-Go @('test', './testing/scenarios', '-run', '^TestAetherLinkX(TLS|TurboUDP)$', '-count=1', '-timeout', '150s')
    }
    Invoke-Go @('build', '-trimpath', '-o', $Output, './main')
    & $Output version
    if ($LASTEXITCODE -ne 0) {
        throw 'Built binary smoke-test failed'
    }
    Get-FileHash -LiteralPath $Output -Algorithm SHA256 | Format-List Algorithm,Hash,Path
}
finally {
    Pop-Location
}
