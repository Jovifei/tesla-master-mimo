[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApiBaseUrl,
    [Parameter(Mandatory = $true)]
    [string]$AuthHost,
    [Parameter(Mandatory = $true)]
    [string]$PublicInfoBaseUrl,
    [string]$SigningPropertiesPath,
    [switch]$SkipLint
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}

function Test-PublicHttpsBaseUrl([string]$Value) {
    $uri = $null
    if (![Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https') {
        Fail 'ApiBaseUrl must be an absolute HTTPS URL.'
    }
    if ($uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') {
        Fail 'ApiBaseUrl must not contain credentials, query, fragment, or a non-root path.'
    }
    $hostName = $uri.Host.ToLowerInvariant().TrimEnd('.')
    if ($hostName -in @('localhost', '127.0.0.1', '::1') -or $hostName.EndsWith('.local')) {
        Fail 'ApiBaseUrl must not use a loopback or local hostname.'
    }
    if ($hostName -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)') {
        Fail 'ApiBaseUrl must not use an RFC1918 private address.'
    }
    if ($hostName -match 'example\.(com|org|net)$') {
        Fail 'ApiBaseUrl must not use an example hostname.'
    }
    return ($uri.GetLeftPart([UriPartial]::Authority) + '/')
}

function Test-PublicHost([string]$Value) {
    $hostName = $Value.Trim().ToLowerInvariant().TrimEnd('.')
    if ([string]::IsNullOrWhiteSpace($hostName) -or $hostName -match '[/:?#\s]' -or $hostName -match '^\.+$') {
        Fail 'AuthHost must be a hostname without scheme or path.'
    }
    if ($hostName -in @('localhost', '127.0.0.1', '::1') -or $hostName.EndsWith('.local')) {
        Fail 'AuthHost must not use a loopback or local hostname.'
    }
    if ($hostName -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)') {
        Fail 'AuthHost must not use an RFC1918 private address.'
    }
    if ($hostName -match 'example\.(com|org|net)$') {
        Fail 'AuthHost must not use an example hostname.'
    }
    return $hostName
}

function Find-AndroidSdk {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    $localProperties = Join-Path $PSScriptRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $sdkPath = ($line -replace '^sdk\.dir=', '').Trim().Replace('\:', ':').Replace('\\', '\')
            $candidates += $sdkPath
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'build-tools')) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

$normalizedApiBaseUrl = Test-PublicHttpsBaseUrl $ApiBaseUrl
$normalizedAuthHost = Test-PublicHost $AuthHost
$normalizedPublicInfoBaseUrl = Test-PublicHttpsBaseUrl $PublicInfoBaseUrl
$normalizedSigningPropertiesPath = $null
if (![string]::IsNullOrWhiteSpace($SigningPropertiesPath)) {
    if (!(Test-Path -LiteralPath $SigningPropertiesPath -PathType Leaf)) {
        Fail "Signing properties file was not found: $SigningPropertiesPath"
    }
    $normalizedSigningPropertiesPath = (Resolve-Path -LiteralPath $SigningPropertiesPath).Path
}
$gradle = Join-Path $PSScriptRoot 'gradlew.bat'
if (!(Test-Path -LiteralPath $gradle)) { Fail "Gradle wrapper not found: $gradle" }

$gradleArgs = @(
    ':app:assembleRelease',
    '-PJOURVOLT_CLOUD_LOGIN=true',
    "-PJOURVOLT_API_BASE_URL=$normalizedApiBaseUrl",
    "-PJOURVOLT_AUTH_HOST=$normalizedAuthHost",
    "-PMATELINK_PUBLIC_INFO_BASE_URL=$normalizedPublicInfoBaseUrl",
    '--no-daemon'
)
if ($normalizedSigningPropertiesPath) {
    $gradleArgs += "-PMATELINK_SIGNING_PROPERTIES_FILE=$normalizedSigningPropertiesPath"
}
if (!$SkipLint) { $gradleArgs = @(':app:lintRelease') + $gradleArgs }

Write-Host 'Building the formal MateLink Pilot APK'
Write-Host "API base: $normalizedApiBaseUrl"
Write-Host "App Link host: $normalizedAuthHost"
Write-Host "Public information base: $normalizedPublicInfoBaseUrl"
$gradleExit = 1
Push-Location $PSScriptRoot
try {
    & $gradle @gradleArgs
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($gradleExit -ne 0) { Fail "Gradle Pilot build failed with exit code $gradleExit." }

$apkName = if ($normalizedSigningPropertiesPath) { 'app-release.apk' } else { 'app-release-unsigned.apk' }
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\$apkName"
if (!(Test-Path -LiteralPath $apk)) { Fail "Release APK was not produced: $apk" }

$sdk = Find-AndroidSdk
if ($null -eq $sdk) { Fail 'Android SDK was not found; cannot verify APK package identity.' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
if (!(Test-Path -LiteralPath $aapt)) { Fail "aapt.exe was not found under $($buildTools.FullName)." }
$badging = (& $aapt dump badging $apk | Out-String)
if ($LASTEXITCODE -ne 0) { Fail 'aapt could not inspect the release APK.' }
if ($badging -notmatch "package: name='com\.matelink'") { Fail 'Release APK package is not com.matelink.' }
if ($badging -match 'com\.jourvolt\.app|com\.matelink\.test\.mock') { Fail 'Release APK contains a test or obsolete package identity.' }

if ($normalizedSigningPropertiesPath) {
    $apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
    if (!(Test-Path -LiteralPath $apksigner)) {
        Fail "apksigner.bat was not found under $($buildTools.FullName)."
    }
    & $apksigner verify --verbose $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'apksigner rejected the formal Release APK.' }
}

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
$apkStatus = if ($normalizedSigningPropertiesPath) { 'SIGNED_RELEASE' } else { 'UNSIGNED_RELEASE' }
Write-Host "APK_PACKAGE=com.matelink"
Write-Host "APK_STATUS=$apkStatus"
Write-Host "APK_SHA256=$hash"
Write-Host "APK_PATH=$apk"
if (!$normalizedSigningPropertiesPath) {
    Write-Host 'A keystore owner must sign this artifact before distribution; this script never handles keystore passwords.'
} else {
    Write-Host 'APK_SIGNATURE=apksigner_verified'
}
