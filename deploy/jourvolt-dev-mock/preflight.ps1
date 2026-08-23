[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env'),
    [switch]$VerifyAppLink,
    [switch]$VerifyPublicDns,
    [switch]$SkipCompose
)

$ErrorActionPreference = 'Stop'
$failures = New-Object System.Collections.Generic.List[string]

if (Test-Path -LiteralPath $EnvFile) {
    $EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
}

function Add-Failure([string]$Message) {
    [void]$failures.Add($Message)
}

function Read-EnvFile([string]$Path) {
    $values = @{}
    if (!(Test-Path -LiteralPath $Path)) {
        Add-Failure("Env file does not exist: $Path")
        return $values
    }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) { continue }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        $values[$name] = $value
    }
    return $values
}

function Get-RequiredValue([hashtable]$Values, [string]$Name) {
    if (!$Values.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        Add-Failure("Missing $Name")
        return $null
    }
    return [string]$Values[$Name]
}

function Test-PublicHttpsUri([string]$Name, [string]$Value) {
    $uri = $null
    if (![Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or [string]::IsNullOrWhiteSpace($uri.Host)) {
        Add-Failure("$Name must be an absolute HTTPS URL")
        return $null
    }
    $hostName = $uri.Host.ToLowerInvariant()
    if ($hostName -eq 'localhost' -or $hostName -eq '0.0.0.0' -or $hostName -eq '127.0.0.1' -or $hostName -eq '::1' -or $hostName.EndsWith('.local')) {
        Add-Failure("$Name must not use a loopback or local hostname")
    }
    if ($hostName -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)') {
        Add-Failure("$Name must not use an RFC1918 private address")
    }
    if ($hostName -match 'example\.(com|org|net)$') {
        Add-Failure("$Name still uses an example hostname")
    }
    return $uri
}

function Test-PublicHost([string]$Name, [string]$Value) {
    $hostName = $Value.Trim().ToLowerInvariant().TrimEnd('.')
    if ([string]::IsNullOrWhiteSpace($hostName) -or $hostName -match '[/:?#\s]' -or $hostName -match '^\.+$') {
        Add-Failure("$Name must be a hostname without scheme or path")
        return $null
    }
    if ($hostName -eq 'localhost' -or $hostName -eq '0.0.0.0' -or $hostName -eq '127.0.0.1' -or $hostName -eq '::1' -or $hostName.EndsWith('.local')) {
        Add-Failure("$Name must not use a loopback or local hostname")
    }
    if ($hostName -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)') {
        Add-Failure("$Name must not use an RFC1918 private address")
    }
    if ($hostName -match 'example\.(com|org|net)$') {
        Add-Failure("$Name still uses an example hostname")
    }
    return $hostName
}

function Test-TokenKey([string]$Value) {
    try {
        $bytes = [Convert]::FromBase64String($Value)
        if ($bytes.Length -ne 32) { Add-Failure('JOURVOLT_TOKEN_KEY_BASE64 must decode to exactly 32 bytes') }
    } catch {
        Add-Failure('JOURVOLT_TOKEN_KEY_BASE64 is not valid standard Base64')
    }
}

function Test-NotPlaceholder([string]$Name, [string]$Value) {
    if ($Value -match 'CHANGE_THIS|REPLACE_WITH|example\.com|<[^>]+>') {
        Add-Failure("$Name still contains a placeholder")
    }
}

function Test-PublicDns([string]$Name, [string]$HostName) {
    $records = @()
    try { $records += @(Resolve-DnsName -Name $HostName -Type A -ErrorAction Stop) } catch { }
    try { $records += @(Resolve-DnsName -Name $HostName -Type AAAA -ErrorAction Stop) } catch { }
    if ($records.Count -eq 0) {
        Add-Failure("$Name has no public A or AAAA DNS record: $HostName")
    }
}

Write-Host 'JourVolt Pilot preflight'
Write-Host "Env file: $EnvFile"
$values = Read-EnvFile $EnvFile

$requiredNames = @('DATABASE_URL', 'POSTGRES_PASSWORD', 'TESLA_CLIENT_ID', 'TESLA_CLIENT_SECRET', 'TESLA_REDIRECT_URI', 'JOURVOLT_APP_LINK_URI', 'JOURVOLT_TOKEN_KEY_BASE64', 'JOURVOLT_API_DOMAIN', 'JOURVOLT_APP_DOMAIN', 'JOURVOLT_ACME_EMAIL')
foreach ($name in $requiredNames) { [void](Get-RequiredValue $values $name) }
foreach ($name in $requiredNames) {
    if ($values.ContainsKey($name) -and ![string]::IsNullOrWhiteSpace([string]$values[$name])) {
        Test-NotPlaceholder $name ([string]$values[$name])
    }
}

$mock = if ($values.ContainsKey('JOURVOLT_ENABLE_MOCK')) { [string]$values['JOURVOLT_ENABLE_MOCK'] } else { '' }
if ($mock.ToLowerInvariant() -ne 'false') { Add-Failure('JOURVOLT_ENABLE_MOCK must be explicitly false') }
$mockHistory = if ($values.ContainsKey('JOURVOLT_ENABLE_MOCK_HISTORY')) { [string]$values['JOURVOLT_ENABLE_MOCK_HISTORY'] } else { '' }
if ($mockHistory.ToLowerInvariant() -eq 'true') { Add-Failure('JOURVOLT_ENABLE_MOCK_HISTORY must not be true in Pilot') }

$redirect = if ($values.ContainsKey('TESLA_REDIRECT_URI')) { Test-PublicHttpsUri 'TESLA_REDIRECT_URI' ([string]$values['TESLA_REDIRECT_URI']) } else { $null }
$appLink = if ($values.ContainsKey('JOURVOLT_APP_LINK_URI')) { Test-PublicHttpsUri 'JOURVOLT_APP_LINK_URI' ([string]$values['JOURVOLT_APP_LINK_URI']) } else { $null }
$apiDomain = if ($values.ContainsKey('JOURVOLT_API_DOMAIN')) { Test-PublicHost 'JOURVOLT_API_DOMAIN' ([string]$values['JOURVOLT_API_DOMAIN']) } else { $null }
$appDomain = if ($values.ContainsKey('JOURVOLT_APP_DOMAIN')) { Test-PublicHost 'JOURVOLT_APP_DOMAIN' ([string]$values['JOURVOLT_APP_DOMAIN']) } else { $null }
if ($null -ne $redirect -and $null -ne $apiDomain -and $redirect.Host.ToLowerInvariant() -ne $apiDomain) {
    Add-Failure('TESLA_REDIRECT_URI host must match JOURVOLT_API_DOMAIN')
}
if ($null -ne $appLink -and $null -ne $appDomain -and $appLink.Host.ToLowerInvariant() -ne $appDomain) {
    Add-Failure('JOURVOLT_APP_LINK_URI host must match JOURVOLT_APP_DOMAIN')
}
if ($null -ne $redirect -and $redirect.AbsolutePath -ne '/v1/auth/tesla/callback') {
    Add-Failure('TESLA_REDIRECT_URI path must be /v1/auth/tesla/callback')
}
if ($null -ne $appLink -and $appLink.AbsolutePath -ne '/oauth/callback') {
    Add-Failure('JOURVOLT_APP_LINK_URI path must be /oauth/callback')
}
if ($VerifyPublicDns) {
    if ($null -ne $apiDomain) { Test-PublicDns 'JOURVOLT_API_DOMAIN' $apiDomain }
    if ($null -ne $appDomain) { Test-PublicDns 'JOURVOLT_APP_DOMAIN' $appDomain }
}
if ($values.ContainsKey('JOURVOLT_TOKEN_KEY_BASE64') -and ![string]::IsNullOrWhiteSpace([string]$values['JOURVOLT_TOKEN_KEY_BASE64'])) {
    Test-TokenKey ([string]$values['JOURVOLT_TOKEN_KEY_BASE64'])
}

$assetLinksPath = Join-Path $PSScriptRoot 'public\.well-known\assetlinks.json'
if (!(Test-Path -LiteralPath $assetLinksPath)) {
    Add-Failure('Missing public/.well-known/assetlinks.json for formal com.matelink App Link')
} else {
    try {
        $assetLinks = Get-Content -LiteralPath $assetLinksPath -Raw | ConvertFrom-Json
        $formalEntries = @($assetLinks | Where-Object { $_.target.package_name -eq 'com.matelink' })
        if ($formalEntries.Count -eq 0) {
            Add-Failure('assetlinks.json does not declare formal package com.matelink')
        }
        $hasFormalFingerprint = $false
        foreach ($entry in $formalEntries) {
            foreach ($fingerprint in @($entry.target.sha256_cert_fingerprints)) {
                if ([string]$fingerprint -match '^(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$') {
                    $hasFormalFingerprint = $true
                }
            }
        }
        if (!$hasFormalFingerprint) {
            Add-Failure('assetlinks.json has no valid formal release SHA-256 fingerprint')
        }
    } catch {
        Add-Failure('assetlinks.json is not valid JSON')
    }
}

$publicRootValue = if ($values.ContainsKey('JOURVOLT_PUBLIC_ROOT')) { [string]$values['JOURVOLT_PUBLIC_ROOT'] } else { '' }
if ([string]::IsNullOrWhiteSpace($publicRootValue)) {
    $legalPublicRoot = Join-Path $PSScriptRoot '..\..\web_matelink\public'
} elseif ([IO.Path]::IsPathRooted($publicRootValue)) {
    $legalPublicRoot = $publicRootValue
} else {
    $legalPublicRoot = Join-Path $PSScriptRoot $publicRootValue
}
$legalPublicRoot = [IO.Path]::GetFullPath($legalPublicRoot)
foreach ($legalPage in @('terms', 'privacy')) {
    $legalFile = Join-Path (Join-Path $legalPublicRoot $legalPage) 'index.html'
    if (!(Test-Path -LiteralPath $legalFile)) {
        Add-Failure("Missing published legal page: $legalFile")
    }
}

if ($VerifyAppLink -and $null -ne $appLink) {
    $assetLinksUri = $appLink.GetLeftPart([UriPartial]::Authority) + '/.well-known/assetlinks.json'
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $assetLinksUri -TimeoutSec 15
        if ($response.StatusCode -ne 200) { Add-Failure("assetlinks.json returned HTTP $($response.StatusCode)") }
        $assetLinks = $response.Content | ConvertFrom-Json
        $formal = @($assetLinks | Where-Object { $_.target.package_name -eq 'com.matelink' })
        if ($formal.Count -eq 0) { Add-Failure('assetlinks.json does not declare formal package com.matelink') }
        if (@($formal | Where-Object { $_.target.sha256_cert_fingerprints.Count -gt 0 -and $_.target.sha256_cert_fingerprints[0] -notmatch 'REPLACE|SHA256' }).Count -eq 0) {
            Add-Failure('assetlinks.json has no formal release SHA-256 fingerprint')
        }
    } catch {
        Add-Failure("Could not verify assetlinks.json: $assetLinksUri")
    }
    foreach ($legalPage in @('terms', 'privacy')) {
        $legalUri = $appLink.GetLeftPart([UriPartial]::Authority) + "/$legalPage/"
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $legalUri -TimeoutSec 15
            if ($response.StatusCode -ne 200 -or $response.Content -notmatch '<html') {
                Add-Failure("Published legal page returned an unexpected response: $legalUri")
            }
        } catch {
            Add-Failure("Could not verify published legal page: $legalUri")
        }
    }
}

if (!$SkipCompose) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Add-Failure('docker command was not found')
    } else {
        Push-Location $PSScriptRoot
        try {
            & docker compose --env-file $EnvFile -f docker-compose.pilot.example.yml config --quiet *> $null
            if ($LASTEXITCODE -ne 0) { Add-Failure('Docker Compose template validation failed; check the private .env') }
        } finally {
            Pop-Location
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Host 'PREFLIGHT=FAIL'
    foreach ($failure in $failures) { Write-Host "- $failure" }
    exit 1
}

Write-Host 'PREFLIGHT=PASS'
Write-Host 'No secret values were printed; the next step is the controlled HTTPS single-car Pilot.'
