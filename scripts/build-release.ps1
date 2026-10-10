param(
    [switch]$SkipBuild,
    [string]$BuildToolsVersion = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $repoRoot "gradlew.bat"
$releaseDir = Join-Path $repoRoot "app\build\outputs\apk\release"
$debugDir = Join-Path $repoRoot "app\build\outputs\apk\debug"
$unsignedApk = Join-Path $releaseDir "app-release-unsigned.apk"
$alignedApk = Join-Path $releaseDir "app-release-aligned.apk"
$signedApk = Join-Path $releaseDir "app-release.apk"
$debugReleaseApk = Join-Path $debugDir "app-release.apk"

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path -LiteralPath $sdkRoot)) {
    throw "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT."
}

$buildToolsRoot = Join-Path $sdkRoot "build-tools"
$buildToolsDir = $null
if ($BuildToolsVersion) {
    $candidate = Join-Path $buildToolsRoot $BuildToolsVersion
    if (Test-Path -LiteralPath $candidate) { $buildToolsDir = $candidate }
} else {
    $buildToolsDir = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName "apksigner.bat") } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $buildToolsDir) {
    throw "No usable build-tools (with apksigner) found under $buildToolsRoot."
}

$zipalign = Join-Path $buildToolsDir "zipalign.exe"
$apksigner = Join-Path $buildToolsDir "apksigner.bat"

$keystore = Join-Path $env:USERPROFILE ".android\debug.keystore"
if (-not (Test-Path -LiteralPath $keystore)) {
    throw "Debug keystore not found: $keystore"
}

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        & $gradlew assembleRelease
        if ($LASTEXITCODE -ne 0) { throw "gradlew assembleRelease failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $unsignedApk)) {
    throw "Unsigned release APK not found: $unsignedApk"
}

& $zipalign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out $signedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed with exit code $LASTEXITCODE" }

Remove-Item -LiteralPath $alignedApk -ErrorAction SilentlyContinue

if (-not (Test-Path -LiteralPath $debugDir)) { New-Item -ItemType Directory -Path $debugDir | Out-Null }
Copy-Item -LiteralPath $signedApk -Destination $debugReleaseApk -Force

$info = Get-Item -LiteralPath $debugReleaseApk
[pscustomobject]@{
    BuildTools = $buildToolsDir
    SignedApk = $signedApk
    CopiedTo = $debugReleaseApk
    SizeMB = [math]::Round($info.Length / 1MB, 2)
    LastWriteTime = $info.LastWriteTime
}
