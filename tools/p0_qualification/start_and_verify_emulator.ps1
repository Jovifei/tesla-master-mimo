[CmdletBinding()]
param(
    [string]$AvdName = "MateLink_P0_Qualification_API35",
    [int]$ConsolePort = 5554,
    [int]$TimeoutSeconds = 45,
    [string]$SdkRoot = "C:\Users\Admin\AppData\Local\Android\Sdk",
    [string]$LogDirectory = "E:\Claude_allow\Download\matelink-avd-qualification"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($AvdName)) {
    Write-Error "AVD name must be explicit."
    exit 2
}
if ($ConsolePort -lt 5554 -or $ConsolePort -gt 5584 -or ($ConsolePort % 2) -ne 0) {
    Write-Error "ConsolePort must be an even emulator console port from 5554 through 5584."
    exit 3
}
if ($TimeoutSeconds -lt 5) {
    Write-Error "TimeoutSeconds must be at least 5."
    exit 4
}

$adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$emulator = Join-Path $SdkRoot "emulator\emulator.exe"
$serial = "emulator-$ConsolePort"

foreach ($path in @($adb, $emulator)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Error "Required Android SDK executable not found: $path"
        exit 5
    }
}

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
if (-not $env:ANDROID_SDK_HOME) {
    $env:ANDROID_SDK_HOME = $env:USERPROFILE
}

& $adb start-server | Out-Host
$attached = @(& $adb devices | Select-String -Pattern "^\S+\s+device\s*$" |
    ForEach-Object { ($_ -split "\s+")[0] } | Where-Object { $_ })
if ($attached -contains $serial) {
    $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null).Trim()
    if ($boot -eq "1") {
        Write-Output "QUALIFICATION=PASS|serial=$serial|existing_device=true|boot_completed=1"
        exit 0
    }
}

New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$stdoutLog = Join-Path $LogDirectory "emulator.stdout.log"
$stderrLog = Join-Path $LogDirectory "emulator.stderr.log"
$pidFile = Join-Path $LogDirectory "emulator.pid"

$process = Start-Process -FilePath $emulator `
    -ArgumentList @(
        "-avd", $AvdName,
        "-port", $ConsolePort,
        "-adb-path", $adb,
        "-no-snapshot",
        "-no-boot-anim",
        "-no-window",
        "-gpu", "swiftshader_indirect"
    ) `
    -WorkingDirectory (Get-Location) `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru
$process.Id | Set-Content -LiteralPath $pidFile -Encoding ascii

try {
    for ($second = 1; $second -le $TimeoutSeconds; $second++) {
        $deviceLine = @(& $adb devices 2>$null | Where-Object { $_ -match "^$([regex]::Escape($serial))\s+device\s*$" })
        if ($deviceLine.Count -gt 0) {
            $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null).Trim()
            if ($boot -eq "1") {
                $qemu = (& $adb -s $serial shell getprop ro.kernel.qemu 2>$null).Trim()
                if ($qemu -eq "1") {
                    Write-Output "QUALIFICATION=PASS|serial=$serial|existing_device=false|boot_completed=1|ro.kernel.qemu=1"
                    exit 0
                }
            }
        }
        if (($second % 5) -eq 0) {
            Write-Output "poll=$second/$TimeoutSeconds|serial=$serial|device_not_ready"
        }
        Start-Sleep -Seconds 1
    }

    Write-Output "QUALIFICATION=NOT_PERFORMED|reason=emulator_not_registered_with_adb|serial=$serial"
    if (Test-Path -LiteralPath $stderrLog) {
        Write-Output "stderr_tail_begin"
        Get-Content -LiteralPath $stderrLog -Tail 20
        Write-Output "stderr_tail_end"
    }
    exit 10
}
finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    $children = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.ParentProcessId -eq $process.Id -and
            $_.CommandLine -like "*$AvdName*"
        }
    foreach ($child in $children) {
        Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
    }
}
