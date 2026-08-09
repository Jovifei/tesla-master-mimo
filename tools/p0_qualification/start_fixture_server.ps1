param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 18080,
    [ValidateSet("normal", "auth_401", "timeout", "no_drives", "no_charges", "parked_partial", "empty", "missing")]
    [string]$Scenario = "normal",
    [string]$LogPath = "E:\temp\matelink-p0-fixture.log"
)

$ErrorActionPreference = "Stop"

$python = (Get-Command python -ErrorAction Stop).Source
$server = Join-Path $PSScriptRoot "fixture_server.py"
$logDir = Split-Path -Parent $LogPath
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$process = Start-Process `
    -FilePath $python `
    -ArgumentList @($server, "--host", $HostName, "--port", "$Port", "--scenario", $Scenario) `
    -RedirectStandardOutput $LogPath `
    -RedirectStandardError "$LogPath.err" `
    -WindowStyle Hidden `
    -PassThru

Start-Sleep -Seconds 2

$health = Invoke-WebRequest -Uri "http://${HostName}:$Port/_health" -UseBasicParsing

[pscustomobject]@{
    pid = $process.Id
    healthStatus = $health.StatusCode
    health = $health.Content
    logPath = $LogPath
} | ConvertTo-Json -Compress
