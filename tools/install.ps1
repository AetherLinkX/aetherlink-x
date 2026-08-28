[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [string]$XrayRoot
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $XrayRoot).Path
$patchPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\patches\aetherlinkx-xray-core.patch')).Path

foreach ($required in @('go.mod', 'infra\conf\xray.go', 'main\distro\all\all.go')) {
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedRoot $required))) {
        throw "Not a compatible Xray-core root; missing $required"
    }
}

& git -C $resolvedRoot apply --check --whitespace=error-all $patchPath 2>$null
if ($LASTEXITCODE -eq 0) {
    if ($PSCmdlet.ShouldProcess($resolvedRoot, 'Apply AetherLink X patch')) {
        & git -C $resolvedRoot apply --whitespace=error-all $patchPath
        if ($LASTEXITCODE -ne 0) {
            throw 'git apply failed unexpectedly after a successful check'
        }
        Write-Host 'AetherLink X installed successfully.'
    }
    return
}

& git -C $resolvedRoot apply --reverse --check $patchPath 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host 'The exact AetherLink X patch is already installed.'
    return
}

throw 'The Xray tree is incompatible or partially modified. No files were changed.'
