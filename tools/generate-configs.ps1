[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$ServerAddress,
    [Parameter(Mandatory)] [string]$RealityServerName,
    [Parameter(Mandatory)] [string]$RealityPublicKey,
    [Parameter(Mandatory)] [string]$RealityPrivateKey,
    [int]$Port = 443,
    [ValidatePattern('^[0-9a-fA-F]{0,16}$')] [string]$ShortId = '0123456789abcdef',
    [string]$RealityTarget,
    [string]$OutputDirectory = '.\generated',
    [string]$XrayBinary
)

$ErrorActionPreference = 'Stop'
if (-not $RealityTarget) { $RealityTarget = "${RealityServerName}:443" }
if (-not $XrayBinary) {
    $XrayBinary = Join-Path $PSScriptRoot '..\dist\windows-amd64\xray-aetherlinkx.exe'
}
$XrayBinary = (Resolve-Path -LiteralPath $XrayBinary).Path
$keyLines = & $XrayBinary aetherlinkx-keygen
if ($LASTEXITCODE -ne 0) { throw 'AetherLink X key generation failed' }
$keys = @{}
foreach ($line in $keyLines) {
    $parts = $line -split ': ', 2
    if ($parts.Count -eq 2) { $keys[$parts[0]] = $parts[1] }
}
foreach ($required in @('ID','Secret','XWingPrivateKey','XWingPublicKey')) {
    if (-not $keys.ContainsKey($required)) { throw "Missing generated field: $required" }
}

$turbo = [ordered]@{
    enabled = $true; maxDatagramAgeMs = 35; destinationCacheSize = 64; maxUdpPayload = 8192
    tcpKeepAliveIdle = 15; tcpKeepAliveInterval = 5; tcpUserTimeout = 10000
    congestion = 'auto'; multipathTcp = $true; muxConcurrency = 8
    xudpConcurrency = 4; xudpProxyUdp443 = 'allow'
}
$stealth = [ordered]@{
    enabled = $true; minChunkSize = 256; maxChunkSize = 1200
    maxPaddingBytes = 128; paddingProbabilityPercent = 25
}
$client = [ordered]@{
    log = [ordered]@{ loglevel = 'warning' }
    inbounds = @([ordered]@{ tag='local-socks'; listen='127.0.0.1'; port=10808; protocol='socks'; settings=[ordered]@{ udp=$true } })
    outbounds = @([ordered]@{
        tag='aetherlinkx-out'; protocol='aetherlinkx'
        settings=[ordered]@{
            address=$ServerAddress; port=$Port; id=$keys.ID; secret=$keys.Secret; turbo=$turbo
            security=[ordered]@{ pqMode='required'; xwingPublicKey=$keys.XWingPublicKey; innerAead=$true }
            stealth=$stealth
        }
        streamSettings=[ordered]@{
            network='raw'; security='reality'
            realitySettings=[ordered]@{ serverName=$RealityServerName; fingerprint='chrome'; password=$RealityPublicKey; shortId=$ShortId; spiderX='/' }
        }
    })
}
$server = [ordered]@{
    log = [ordered]@{ loglevel = 'warning' }
    inbounds = @([ordered]@{
        tag='AETHERLINK_X_REALITY'; listen='0.0.0.0'; port=$Port; protocol='aetherlinkx'
        settings=[ordered]@{
            users=@([ordered]@{ id=$keys.ID; secret=$keys.Secret; email='static-alx@local'; level=0 })
            handshakeTimeoutSeconds=4; turbo=$turbo
            security=[ordered]@{ pqMode='required'; xwingPrivateKey=$keys.XWingPrivateKey; innerAead=$true }
            stealth=$stealth
        }
        streamSettings=[ordered]@{
            network='raw'; security='reality'
            realitySettings=[ordered]@{ show=$false; target=$RealityTarget; xver=0; serverNames=@($RealityServerName); privateKey=$RealityPrivateKey; shortIds=@($ShortId) }
        }
        sniffing=[ordered]@{ enabled=$true; routeOnly=$true; destOverride=@('http','tls','quic') }
    })
    outbounds = @(
        [ordered]@{ tag='DIRECT'; protocol='freedom'; settings=[ordered]@{} },
        [ordered]@{ tag='BLOCK'; protocol='blackhole'; settings=[ordered]@{} }
    )
    routing = [ordered]@{
        rules = @(
            [ordered]@{ type='field'; ip=@('0.0.0.0/8','10.0.0.0/8','100.64.0.0/10','127.0.0.0/8','169.254.0.0/16','172.16.0.0/12','192.168.0.0/16','198.18.0.0/15','224.0.0.0/4','240.0.0.0/4','::1/128','fc00::/7','fe80::/10','ff00::/8'); outboundTag='BLOCK' },
            [ordered]@{ type='field'; domain=@('full:localhost','domain:local'); outboundTag='BLOCK' },
            [ordered]@{ type='field'; protocol=@('bittorrent'); outboundTag='BLOCK' }
        )
    }
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$clientPath = Join-Path $OutputDirectory 'client.json'
$serverPath = Join-Path $OutputDirectory 'server.json'
$profilePath = Join-Path $OutputDirectory 'remnawave-profile.json'
$client | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $clientPath -Encoding utf8
$server | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $serverPath -Encoding utf8
$server | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $profilePath -Encoding utf8
Write-Host "Generated $clientPath, $serverPath and $profilePath"
Write-Warning 'These files contain live credentials. Restrict access and do not commit them.'
