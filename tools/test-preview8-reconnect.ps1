param(
    [Parameter(Mandatory)][string]$ExpectedExitIp,
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [string]$Package = 'io.aetherlinkx.client.debug',
    [ValidateRange(1, 20)][int]$Attempts = 3
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB not found: $Adb" }
$results = @()

& $Adb logcat -c

1..$Attempts | ForEach-Object {
    $focus = (& $Adb shell dumpsys window | Select-String 'mCurrentFocus' | Select-Object -First 1).ToString()
    if ($focus -notmatch [regex]::Escape($Package)) {
        throw "AetherLink X is not foreground before reconnect attempt $_"
    }

    & $Adb shell input tap 540 850
    Start-Sleep -Seconds 2
    & $Adb shell input tap 540 850
    Start-Sleep -Seconds 5

    $exitIp = (& $Adb shell curl -4 --max-time 20 --silent 'https://api.ipify.org').Trim()
    $results += [pscustomobject]@{
        Attempt = $_
        ExitMatchesServer = ($exitIp -eq $ExpectedExitIp)
    }
}

$failureLines = @(& $Adb logcat -d -v brief | Select-String 'VPN start failed|certificate pin mismatch|AndroidRuntime.*FATAL')
[pscustomobject]@{
    Attempts = $results
    FailureLogCount = $failureLines.Count
} | ConvertTo-Json -Depth 4
