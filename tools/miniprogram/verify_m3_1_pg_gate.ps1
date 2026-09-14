param(
  [string]$EvidencePath = ""
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$goRoot = Join-Path $repoRoot 'deploy\jourvolt-dev-mock'
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
  $EvidencePath = Join-Path $repoRoot 'm3-1-pg-test.json'
}

function Write-GateResult([string]$Status, [string]$Reason, [int]$ExitCode) {
  $result = [ordered]@{
    gate = 'm3.1-postgresql'
    status = $Status
    reason = $Reason
    evidence = $EvidencePath
    generated_at_utc = [DateTime]::UtcNow.ToString('o')
  }
  $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $EvidencePath -Encoding utf8
  Write-Output ($result | ConvertTo-Json -Compress)
  exit $ExitCode
}

if ([string]::IsNullOrWhiteSpace($env:JOURVOLT_TEST_DATABASE_URL)) {
  Write-GateResult 'BLOCKED' 'JOURVOLT_TEST_DATABASE_URL is required; no production DSN is accepted as a fallback.' 2
}

$jsonPath = Join-Path ([IO.Path]::GetTempPath()) ('matelink-m3-1-go-' + [guid]::NewGuid().ToString('N') + '.json')
Push-Location $goRoot
try {
  & go test ./... -count=1 -json *> $jsonPath
  $goExit = $LASTEXITCODE
} finally {
  Pop-Location
}

$rows = @(Get-Content -LiteralPath $jsonPath | ConvertFrom-Json)
$skipCount = @($rows | Where-Object Action -eq 'skip').Count
$failCount = @($rows | Where-Object Action -eq 'fail').Count
if ($goExit -ne 0 -or $failCount -gt 0) {
  Write-GateResult 'FAIL' ("go test exit=$goExit failures=$failCount skips=$skipCount") 1
}
if ($skipCount -gt 0) {
  Write-GateResult 'FAIL' ("required PostgreSQL gate still has $skipCount skipped test events") 1
}
Write-GateResult 'PASS' 'go test completed with zero failures and zero skipped tests.' 0
