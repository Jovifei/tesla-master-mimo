[CmdletBinding()]
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env'),
    [switch]$NoEdge,
    [switch]$VerifyAppLink,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'docker-compose.pilot.example.yml'
$preflightFile = Join-Path $PSScriptRoot 'preflight.ps1'

if (!(Test-Path -LiteralPath $EnvFile)) {
    Write-Error "Env file does not exist: $EnvFile"
    exit 1
}
if (!(Test-Path -LiteralPath $composeFile)) {
    Write-Error "Pilot Compose file does not exist: $composeFile"
    exit 1
}

$resolvedEnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    Write-Error 'docker command was not found'
    exit 1
}

Write-Host 'JourVolt controlled Pilot startup'
Write-Host "Env file: $resolvedEnvFile"

$preflightArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $preflightFile, '-EnvFile', $resolvedEnvFile)
if (!$NoEdge) { $preflightArgs += '-VerifyPublicDns' }
if ($VerifyAppLink) { $preflightArgs += '-VerifyAppLink' }
$powershellCommand = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powershellCommand) {
    $powershellCommand = Get-Command powershell -ErrorAction Stop
}
& $powershellCommand.Source @preflightArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Pilot preflight failed; no services were started'
    exit $LASTEXITCODE
}

$composeBaseArgs = @('--env-file', $resolvedEnvFile, '-f', $composeFile)
if (!$NoEdge) {
    $composeBaseArgs += @('--profile', 'edge')
}

Write-Host 'Validating the exact Compose configuration'
& docker compose @composeBaseArgs config --quiet
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Docker Compose validation failed; no services were started'
    exit $LASTEXITCODE
}

$upArgs = @($composeBaseArgs + @('up'))
if (!$SkipBuild) {
    $upArgs += '--build'
}
$upArgs += '-d'

Write-Host 'Starting the controlled Pilot services'
& docker compose @upArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Pilot services failed to start'
    exit $LASTEXITCODE
}

Write-Host 'Checking the API readiness endpoint inside the API container'
$readyArgs = @($composeBaseArgs + @('exec', '-T', 'jourvolt-api', 'wget', '-q', '-O', '-', 'http://127.0.0.1:8080/readyz'))
& docker compose @readyArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error 'API readiness check failed; inspect docker compose logs jourvolt-api'
    exit $LASTEXITCODE
}

Write-Host 'PILOT_DEPLOY=PASS'
Write-Host 'The controlled services are running. This does not prove Tesla OAuth or a real vehicle until the external Pilot flow is completed.'
