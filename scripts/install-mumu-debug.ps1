param(
    [string]$ApkPath = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$PackageName = "com.quickssh.app"
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    [array]$candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "C:\Program Files\Netease\MuMuPlayerGlobal-12.0\shell\adb.exe",
        "C:\Program Files\Netease\MuMu Player 12\shell\adb.exe",
        "C:\Program Files\Netease\MuMuPlayer-12.0\shell\adb.exe",
        "C:\Program Files\MuMu\emulator\nemu\vmonitor\bin\adb_server.exe"
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) }

    if ($candidates.Count -gt 0) {
        return $candidates[0]
    }

    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "adb.exe was not found. Start MuMu or install Android platform-tools, then run this script again."
}

$resolvedApk = Resolve-Path -LiteralPath $ApkPath -ErrorAction SilentlyContinue
if (-not $resolvedApk) {
    Write-Host "Debug APK not found. Building app-debug.apk..."
    & .\gradlew.bat assembleDebug
    $resolvedApk = Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop
}

$adb = Find-Adb
Write-Host "Using ADB: $adb"

& $adb start-server | Out-Host
$devicesOutput = & $adb devices
$deviceLines = $devicesOutput | Where-Object { $_ -match "\tdevice$" }

if (-not $deviceLines -or $deviceLines.Count -eq 0) {
    $mumuPorts = @(7555, 16384, 16416, 16448, 16512)
    foreach ($port in $mumuPorts) {
        & $adb connect "127.0.0.1:$port" | Out-Null
    }
    $devicesOutput = & $adb devices
    $deviceLines = $devicesOutput | Where-Object { $_ -match "\tdevice$" }
}

if (-not $deviceLines -or $deviceLines.Count -eq 0) {
    Write-Host ($devicesOutput -join [Environment]::NewLine)
    throw "No Android device is connected. Start MuMu emulator and wait until Android is fully booted."
}

$targetDevice = ($deviceLines[0] -split "\s+")[0]
Write-Host "Installing $resolvedApk to $targetDevice..."
& $adb -s $targetDevice install -r -d $resolvedApk.Path | Out-Host

Write-Host "Launching $PackageName..."
& $adb -s $targetDevice shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Host
Write-Host "Done."
