[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Sha256CertificateFingerprint,
    [string]$OutputPath = 'public\.well-known\assetlinks.json',
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}

$hex = ($Sha256CertificateFingerprint -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
if ($hex.Length -ne 64 -or $hex -notmatch '^[0-9A-F]{64}$') {
    Fail 'Sha256CertificateFingerprint must contain exactly 32 bytes of hexadecimal data.'
}
$normalized = (($hex -split '(.{2})' | Where-Object { $_ -ne '' }) -join ':')

$publicRootPath = Join-Path $PSScriptRoot 'public'
$publicRoot = if (Test-Path -LiteralPath $publicRootPath) {
    (Resolve-Path -LiteralPath $publicRootPath).Path
} else {
    [IO.Path]::GetFullPath($publicRootPath)
}
$candidate = if ([IO.Path]::IsPathRooted($OutputPath)) {
    [IO.Path]::GetFullPath($OutputPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $OutputPath))
}
$publicPrefix = $publicRoot.TrimEnd('\') + '\'
if (!$candidate.StartsWith($publicPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    Fail 'OutputPath must remain inside the deployment public directory.'
}

$payload = @(
    [ordered]@{
        relation = @('delegate_permission/common.handle_all_urls')
        target = [ordered]@{
            namespace = 'android_app'
            package_name = 'com.matelink'
            sha256_cert_fingerprints = @($normalized)
        }
    }
)
$json = $payload | ConvertTo-Json -Depth 5

if ($WhatIf) {
    Write-Host "ASSETLINKS_OUTPUT=$candidate"
    Write-Host "ASSETLINKS_FINGERPRINT=$normalized"
    Write-Host 'ASSETLINKS_WRITE=SKIPPED'
    exit 0
}

$parent = Split-Path -Parent $candidate
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($candidate, $json + [Environment]::NewLine, $utf8)
Write-Host "ASSETLINKS_OUTPUT=$candidate"
Write-Host "ASSETLINKS_FINGERPRINT=$normalized"
Write-Host 'ASSETLINKS_WRITE=PASS'
