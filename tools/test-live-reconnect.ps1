param(
    [Parameter(Mandatory = $true)]
    [string] $XrayPath,

    [Parameter(Mandatory = $true)]
    [string] $SubscriptionUrl,

    [ValidateRange(1, 50)]
    [int] $Attempts = 8,

    [ValidateRange(1025, 65535)]
    [int] $SocksPort = 19080,

    [switch] $DisableMultipathTcp,

    [switch] $ProbeRealityWithVless,

    [switch] $ProbeFirstSubscriptionVless,

    [ValidateRange(10, 120)]
    [int] $RequestTimeoutSeconds = 40
)

$ErrorActionPreference = 'Stop'

function ConvertFrom-Base64Url([string] $Value) {
    $encoded = $Value.Replace('-', '+').Replace('_', '/')
    while ($encoded.Length % 4) { $encoded += '=' }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
}

function Get-TestLink([string] $Url) {
    $response = Invoke-WebRequest -Uri $Url -Headers @{
        'User-Agent' = 'AetherLinkX/diagnostic'
        'Accept' = '*/*'
    } -UseBasicParsing -TimeoutSec 30
    $body = $response.Content.Trim()
    try {
        $decoded = ConvertFrom-Base64Url $body
        if ($decoded -match '://') { $body = $decoded }
    } catch {}
    $scheme = if ($ProbeFirstSubscriptionVless) { 'vless' } else { 'aetherlinkx' }
    $link = $body -split "`r?`n" |
        Where-Object { $_ -match "^${scheme}://" } |
        Select-Object -First 1
    if (-not $link) { throw "Subscription does not contain $scheme" }
    return $link
}

function New-ClientConfig([string] $Link, [int] $Port) {
    $uri = [Uri]$Link
    $query = [Web.HttpUtility]::ParseQueryString($uri.Query)
    $fingerprint = $query['fp']
    if (-not $fingerprint) { $fingerprint = 'chrome' }

    if ($ProbeRealityWithVless -or $ProbeFirstSubscriptionVless) {
        $protocol = 'vless'
        $protocolSettings = [ordered]@{
            vnext = @([ordered]@{
                address = $uri.Host
                port = $uri.Port
                users = @([ordered]@{
                    id = $uri.UserInfo
                    encryption = 'none'
                    flow = ''
                })
            })
        }
    } else {
        $turbo = ConvertFrom-Base64Url $query['turbo'] | ConvertFrom-Json
        if ($DisableMultipathTcp -and $turbo) {
            $turbo.multipathTcp = $false
        }
        $security = ConvertFrom-Base64Url $query['alxSecurity'] | ConvertFrom-Json
        $stealth = ConvertFrom-Base64Url $query['stealth'] | ConvertFrom-Json
        $protocol = 'aetherlinkx'
        $protocolSettings = [ordered]@{
            address = $uri.Host
            port = $uri.Port
            id = $uri.UserInfo
            secret = $query['secret']
            allowInsecureTransport = $false
            turbo = $turbo
            security = $security
            stealth = $stealth
        }
    }

    return [ordered]@{
        log = @{ loglevel = 'debug' }
        inbounds = @([ordered]@{
            tag = 'socks'
            listen = '127.0.0.1'
            port = $Port
            protocol = 'socks'
            settings = @{ auth = 'noauth'; udp = $true }
        })
        outbounds = @(
            [ordered]@{
                tag = 'proxy'
                protocol = $protocol
                settings = $protocolSettings
                streamSettings = [ordered]@{
                    network = 'tcp'
                    security = 'reality'
                    realitySettings = [ordered]@{
                        serverName = $query['sni']
                        fingerprint = $fingerprint
                        shortId = $query['sid']
                        password = $query['pbk']
                        spiderX = $query['spx']
                    }
                }
                mux = @{ enabled = $false }
            },
            @{ tag = 'direct'; protocol = 'freedom' }
        )
        routing = @{ rules = @(
            @{ type = 'field'; outboundTag = 'proxy'; network = 'tcp,udp' }
        ) }
    } | ConvertTo-Json -Depth 30 -Compress
}

function Wait-Socks([Diagnostics.Process] $Process, [int] $Port) {
    for ($index = 0; $index -lt 50; $index++) {
        if ($Process.HasExited) { return $false }
        $socket = [Net.Sockets.TcpClient]::new()
        try {
            if ($socket.ConnectAsync('127.0.0.1', $Port).Wait(200) -and $socket.Connected) {
                return $true
            }
        } catch {
        } finally {
            $socket.Dispose()
        }
        Start-Sleep -Milliseconds 100
    }
    return $false
}

$link = Get-TestLink $SubscriptionUrl
$config = New-ClientConfig $link $SocksPort

for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = (Resolve-Path -LiteralPath $XrayPath).Path
    $start.Arguments = 'run -c stdin:'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    $process.StandardInput.Write($config)
    $process.StandardInput.Close()

    $ready = Wait-Socks $process $SocksPort
    $httpCode = ''
    $curlExit = -1
    if ($ready) {
        $curlArguments = @(
            '-sS',
            '--proxy', "socks5h://127.0.0.1:$SocksPort",
            '--connect-timeout', $RequestTimeoutSeconds.ToString(),
            '--max-time', $RequestTimeoutSeconds.ToString(),
            '-o', 'NUL',
            '-w', '%{http_code}',
            'https://cp.cloudflare.com/generate_204'
        )
        $httpCode = & curl.exe @curlArguments
        $curlExit = $LASTEXITCODE
    }

    $running = -not $process.HasExited
    if ($running) { $process.Kill($true) }
    $process.WaitForExit()

    [pscustomobject]@{
        Attempt = $attempt
        ListenerReady = $ready
        CurlExit = $curlExit
        HttpStatus = $httpCode
        CoreStayedRunning = $running
    } | ConvertTo-Json -Compress | Write-Output

    Start-Sleep -Milliseconds 600
}
