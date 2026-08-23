[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'

$deployRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $deployRoot '..\..')).Path
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
$repoPrefix = $repoRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar

if ($outputPath.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Output must be outside the repository: $outputPath"
}
if (Test-Path -LiteralPath $outputPath) {
    throw "Output already exists; choose a new directory: $outputPath"
}
if (Test-Path -LiteralPath (Join-Path $deployRoot '.env')) {
    throw 'Refusing to package while deploy/jourvolt-dev-mock/.env exists'
}

$sourceEnvExample = Join-Path $deployRoot '.env.example'
$sourcePublicRoot = Join-Path $repoRoot 'web_matelink\public'
foreach ($requiredPath in @(
        $sourceEnvExample,
        (Join-Path $sourcePublicRoot 'terms\index.html'),
        (Join-Path $sourcePublicRoot 'privacy\index.html'),
        (Join-Path $sourcePublicRoot 'matelink-info.css')
    )) {
    if (!(Test-Path -LiteralPath $requiredPath)) {
        throw "Required bundle input is missing: $requiredPath"
    }
}

New-Item -ItemType Directory -Path $outputPath -Force | Out-Null

foreach ($item in Get-ChildItem -LiteralPath $deployRoot -Force) {
    if ($item.Name -in @('.env', 'backups')) { continue }
    Copy-Item -LiteralPath $item.FullName -Destination $outputPath -Recurse -Force
}

$bundlePublicRoot = Join-Path $outputPath 'public'
New-Item -ItemType Directory -Path $bundlePublicRoot -Force | Out-Null
foreach ($item in Get-ChildItem -LiteralPath $sourcePublicRoot -Force) {
    Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $bundlePublicRoot $item.Name) -Recurse -Force
}

$bundleEnvExample = Join-Path $outputPath '.env.example'
$envText = [IO.File]::ReadAllText($sourceEnvExample)
if ($envText -match '(?m)^# JOURVOLT_PUBLIC_ROOT=.*$') {
    $envText = [Text.RegularExpressions.Regex]::Replace($envText, '(?m)^# JOURVOLT_PUBLIC_ROOT=.*$', 'JOURVOLT_PUBLIC_ROOT=./public')
} elseif ($envText -notmatch '(?m)^JOURVOLT_PUBLIC_ROOT=') {
    $envText = $envText.TrimEnd() + [Environment]::NewLine + 'JOURVOLT_PUBLIC_ROOT=./public' + [Environment]::NewLine
}
[IO.File]::WriteAllText($bundleEnvExample, $envText, (New-Object Text.UTF8Encoding($false)))

$manifest = [ordered]@{
    product = 'JourVolt controlled Pilot'
    source = 'app_mimo/deploy/jourvolt-dev-mock'
    static_public_root = './public'
    secrets_included = $false
    next_steps = @(
        'Copy .env.example to .env and fill private Pilot values',
        'Place the formal assetlinks.json in public/.well-known/',
        'Run preflight.ps1 before docker compose or pilot-up.ps1'
    )
}
[IO.File]::WriteAllText(
    (Join-Path $outputPath 'PILOT-BUNDLE-MANIFEST.json'),
    ($manifest | ConvertTo-Json -Depth 4),
    (New-Object Text.UTF8Encoding($false))
)

Write-Host 'PILOT_BUNDLE=PASS'
Write-Host "Output: $outputPath"
Write-Host 'Secrets included: false'
Write-Host 'Static public root: ./public'
Write-Host 'Next: copy .env.example to .env, fill private values, and run preflight.ps1.'
