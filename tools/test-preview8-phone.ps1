param(
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [ValidateRange(1, 100)][int]$Attempts = 10
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB not found: $Adb" }
$results = @()

1..$Attempts | ForEach-Object {
    $value = (& $Adb shell curl -4 --max-time 20 --silent --output /dev/null --write-out '%{http_code}:%{time_total}' 'https://speed.cloudflare.com/__down?bytes=65536').Trim()
    $parts = $value.Split(':', 2)
    $seconds = [double]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture)
    $results += [pscustomobject]@{
        Code = $parts[0]
        Seconds = $seconds
    }
}

$successes = @($results | Where-Object Code -eq '200').Count
$average = ($results | Measure-Object Seconds -Average).Average
$maximum = ($results | Measure-Object Seconds -Maximum).Maximum

[pscustomobject]@{
    Attempts = $results.Count
    Successes = $successes
    Failures = $results.Count - $successes
    AverageSeconds = [math]::Round($average, 3)
    MaxSeconds = [math]::Round($maximum, 3)
} | ConvertTo-Json
