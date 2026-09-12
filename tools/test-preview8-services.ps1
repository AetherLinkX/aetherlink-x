param(
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB not found: $Adb" }
$targets = [ordered]@{
    Google = 'https://www.google.com/generate_204'
    YouTube = 'https://www.youtube.com/generate_204'
    Telegram = 'https://telegram.org/'
    Cloudflare = 'https://cloudflare.com/cdn-cgi/trace'
}

$results = foreach ($entry in $targets.GetEnumerator()) {
    $value = (& $Adb shell curl -4 --max-time 20 --silent --output /dev/null --write-out '%{http_code}:%{time_total}' $entry.Value).Trim()
    $parts = $value.Split(':', 2)
    [pscustomobject]@{
        Service = $entry.Key
        HttpCode = $parts[0]
        Seconds = [math]::Round([double]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture), 3)
        Reachable = $parts[0] -match '^(200|204|301|302)$'
    }
}

$results | ConvertTo-Json
