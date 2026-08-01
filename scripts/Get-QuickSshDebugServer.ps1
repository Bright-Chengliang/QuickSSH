param(
    [switch]$AsEnvironment
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$credentialPath = Join-Path $repoRoot ".workbuddy\secrets\quickssh-debug-server.credential.xml"
$localConfigPath = Join-Path $repoRoot ".workbuddy\quickssh-debug-server.local.ps1"

if (-not (Test-Path -LiteralPath $credentialPath)) {
    throw "Debug server credential was not found: $credentialPath"
}

if (Test-Path -LiteralPath $localConfigPath) {
    . $localConfigPath
}

$credential = Import-Clixml -LiteralPath $credentialPath
$password = $credential.GetNetworkCredential().Password
$hostAddress = $env:QUICKSSH_DEBUG_HOST
$workDirectory = $env:QUICKSSH_DEBUG_WORKDIR

if (-not $hostAddress) { $hostAddress = $QUICKSSH_DEBUG_HOST }
if (-not $workDirectory) { $workDirectory = $QUICKSSH_DEBUG_WORKDIR }

if (-not $hostAddress) {
    throw "QUICKSSH_DEBUG_HOST is not configured. Set it in .workbuddy\quickssh-debug-server.local.ps1 or as an environment variable."
}

if (-not $workDirectory) {
    throw "QUICKSSH_DEBUG_WORKDIR is not configured. Set it in .workbuddy\quickssh-debug-server.local.ps1 or as an environment variable."
}

if ($AsEnvironment) {
    $env:QUICKSSH_DEBUG_HOST = $hostAddress
    $env:QUICKSSH_DEBUG_USERNAME = $credential.UserName
    $env:QUICKSSH_DEBUG_PASSWORD = $password
    $env:QUICKSSH_DEBUG_WORKDIR = $workDirectory
}

[pscustomobject]@{
    Host = $hostAddress
    Username = $credential.UserName
    Password = "<hidden>"
    WorkDirectory = $workDirectory
    CredentialPath = $credentialPath
    EnvironmentUpdated = [bool]$AsEnvironment
}
