param(
    [Parameter(Mandatory = $true)]
    [string]$AndroidSerial,

    [Parameter(Mandatory = $true)]
    [string]$TestClass,

    [string]$AdbPath = "C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"

$guard = Join-Path $PSScriptRoot "assert_emulator_only.ps1"
& powershell -ExecutionPolicy Bypass -File $guard -AndroidSerial $AndroidSerial -AdbPath $AdbPath
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $AdbPath -s $AndroidSerial shell am instrument -w -r -e class $TestClass com.matelink.test/androidx.test.runner.AndroidJUnitRunner
$instrumentExit = $LASTEXITCODE

& $AdbPath -s $AndroidSerial shell pm path com.matelink
& $AdbPath -s $AndroidSerial shell pm path com.matelink.test

exit $instrumentExit
