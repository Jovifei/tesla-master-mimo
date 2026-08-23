param(
    [string]$BaseUrl = 'http://127.0.0.1:18090'
)

$ErrorActionPreference = 'Stop'

function Get-Status([string]$Path) {
    $response = Invoke-RestMethod -Uri "$BaseUrl$Path"
    if ($response.status -ne 'ok') {
        throw "$Path did not report status=ok"
    }
    return $response.status
}

$health = Get-Status '/healthz'
$ready = Get-Status '/readyz'
$login = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/v1/dev/mock-login" `
    -ContentType 'application/json' `
    -Body '{}'

$accessToken = $login.access_token
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw 'Mock login did not return an access token'
}

$headers = @{ Authorization = "Bearer $accessToken" }
$revokedStatus = $null
try {
    $vehicles = Invoke-RestMethod -Uri "$BaseUrl/v1/vehicles" -Headers $headers
    $vehicleList = @($vehicles.vehicles)
    if ($vehicleList.Count -eq 0) {
        throw 'Mock login returned no vehicles'
    }

    $vehicleId = [int]$vehicleList[0].id
    $snapshot = Invoke-RestMethod `
        -Uri "$BaseUrl/api/matelink/v1/cars/$vehicleId/snapshot" `
        -Headers $headers
    $drives = Invoke-RestMethod `
        -Uri "$BaseUrl/api/v1/cars/$vehicleId/drives" `
        -Headers $headers
    $charges = Invoke-RestMethod `
        -Uri "$BaseUrl/api/v1/cars/$vehicleId/charges" `
        -Headers $headers
    $driveRecords = @($drives.data.drives)
    $chargeRecords = @($charges.data.charges)

    [pscustomobject]@{
        result = 'LOCAL MOCK PASS'
        health = $health
        ready = $ready
        vehicle_count = $vehicleList.Count
        vehicle_id = $vehicleId
        vehicle_state = $snapshot.data.status.state
        battery_level = $snapshot.data.status.battery_details.battery_level
        snapshot_source = $snapshot.data.source
        drive_count = $driveRecords.Count
        charge_count = $chargeRecords.Count
    } | ConvertTo-Json -Compress
}
finally {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/v1/session/logout" `
        -Headers $headers | Out-Null
}

try {
    Invoke-WebRequest -Uri "$BaseUrl/v1/vehicles" -Headers $headers -UseBasicParsing | Out-Null
    $revokedStatus = 200
}
catch {
    $revokedStatus = [int]$_.Exception.Response.StatusCode
}

if ($revokedStatus -ne 401) {
    throw "Revoked access token returned HTTP $revokedStatus instead of 401"
}

Write-Output 'logout_revocation=PASS'
