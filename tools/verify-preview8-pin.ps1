param(
    [string]$LinkPath,
    [string]$CertificatePath
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($LinkPath)) {
    $LinkPath = Join-Path $repoRoot 'alx-preview8-test-link.private.txt'
}
if ([string]::IsNullOrWhiteSpace($CertificatePath)) {
    $CertificatePath = Join-Path $repoRoot 'turbo-fullchain.public.pem'
}

$rawLink = (Get-Content -Raw -LiteralPath $LinkPath).Trim()
$uri = [Uri]$rawLink
$parameters = @{}
foreach ($part in $uri.Query.TrimStart('?').Split('&')) {
    if ([string]::IsNullOrWhiteSpace($part)) { continue }
    $pair = $part.Split('=', 2)
    $key = [Uri]::UnescapeDataString($pair[0])
    $value = if ($pair.Count -gt 1) { [Uri]::UnescapeDataString($pair[1]) } else { '' }
    $parameters[$key] = $value
}
$linkPin = $parameters['pin']

$pem = Get-Content -Raw -LiteralPath $CertificatePath
$certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem($pem)
$spki = $certificate.PublicKey.ExportSubjectPublicKeyInfo()
$hash = [System.Security.Cryptography.SHA256]::HashData([byte[]]$spki)
$calculatedPin = [Convert]::ToBase64String($hash).TrimEnd('=').Replace('+', '-').Replace('/', '_')

[pscustomobject]@{
    LinkPin       = $linkPin
    CalculatedPin = $calculatedPin
    Equal         = ($linkPin -eq $calculatedPin)
    LinkLength    = $rawLink.Length
    LinkPinLength = $linkPin.Length
    QueryKeys     = @($parameters.Keys | Sort-Object)
    Subject       = $certificate.Subject
    Thumbprint    = $certificate.Thumbprint
} | ConvertTo-Json
