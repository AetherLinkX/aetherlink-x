[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$bundleRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sumPath = Join-Path $bundleRoot 'SHA256SUMS'
if (-not (Test-Path -LiteralPath $sumPath)) {
    throw 'SHA256SUMS is missing'
}

$failed = $false
foreach ($line in Get-Content -LiteralPath $sumPath) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '  ', 2
    if ($parts.Count -ne 2) { throw "Malformed checksum line: $line" }
    $expected = $parts[0].Trim().ToUpperInvariant()
    $relative = $parts[1].Trim().Replace('/', [IO.Path]::DirectorySeparatorChar)
    $path = Join-Path $bundleRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        if ($relative -like 'dist\AetherLink-X-*.zip' -or $relative -like 'dist\AetherLink-X-*.zip.sha256') {
            Write-Verbose "Optional outer distribution artifact is not present: $relative"
            continue
        }
        Write-Error "Missing: $relative"
        $failed = $true
        continue
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    if ($actual -ne $expected) {
        Write-Error "Checksum mismatch: $relative"
        $failed = $true
    }
}
if ($failed) { throw 'Bundle verification failed' }
Write-Host 'All AetherLink X bundle checksums are valid.'
