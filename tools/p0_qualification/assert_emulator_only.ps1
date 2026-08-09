param(
    [Parameter(Mandatory = $true)]
    [string]$AndroidSerial,

    [string]$AdbPath = "C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($AndroidSerial)) {
    Write-Error "ANDROID_SERIAL must be explicit. Refusing to auto-select a device."
    exit 2
}

if ($AndroidSerial -notlike "emulator-*") {
    Write-Error "Refusing non-emulator serial '$AndroidSerial'."
    exit 3
}

if (-not (Test-Path -LiteralPath $AdbPath)) {
    Write-Error "adb not found at '$AdbPath'."
    exit 4
}

$devices = & $AdbPath devices | Select-String -Pattern "^\S+\s+device$|^\S+\s+device\s"
$deviceSerials = @($devices | ForEach-Object { ($_ -split "\s+")[0] } | Where-Object { $_ })

if ($deviceSerials -notcontains $AndroidSerial) {
    Write-Error "Emulator '$AndroidSerial' is not an attached adb device."
    exit 5
}

$qemu = (& $AdbPath -s $AndroidSerial shell getprop ro.kernel.qemu).Trim()
if ($qemu -ne "1") {
    Write-Error "Serial '$AndroidSerial' is not confirmed as an Android emulator (ro.kernel.qemu=$qemu)."
    exit 6
}

$state = (& $AdbPath -s $AndroidSerial get-state).Trim()
if ($state -ne "device") {
    Write-Error "Emulator '$AndroidSerial' is not ready (state=$state)."
    exit 7
}

Write-Output "OK emulator-only guard passed for $AndroidSerial"
