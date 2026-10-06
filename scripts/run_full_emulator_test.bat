@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo  QuickSSH + Termux + OpenCode End-to-End Test Runner
echo ========================================================

set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set TARGET_DEVICE=127.0.0.1:16416

if not exist %ADB% (
    echo [ERROR] ADB not found at %ADB%
    exit /b 1
)

echo [1/6] Verifying Target Device (%TARGET_DEVICE%)...
%ADB% connect %TARGET_DEVICE%
%ADB% -s %TARGET_DEVICE% get-state >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Unable to communicate with MuMu Instance 1 at %TARGET_DEVICE%.
    echo Please make sure MuMu Instance 1 is running and try again.
    exit /b 1
)
echo [OK] Connected to %TARGET_DEVICE%

echo [2/6] Installing Termux & QuickSSH APKs...
%ADB% -s %TARGET_DEVICE% install -r "C:\Users\admin\Downloads\termux-app-v0.118.1.apk"
%ADB% -s %TARGET_DEVICE% install -r "app\build\outputs\apk\debug\app-debug.apk"
echo [OK] APKs installed.

echo [3/6] Launching QuickSSH on %TARGET_DEVICE%...
%ADB% -s %TARGET_DEVICE% shell am start -n com.quickssh.app/.MainActivity
timeout /t 3 >nul

echo [4/6] Pushing setup scripts to device...
%ADB% -s %TARGET_DEVICE% push scripts\termux_setup_opencode.sh /data/local/tmp/termux_setup_opencode.sh
%ADB% -s %TARGET_DEVICE% push scripts\bridge_to_quickssh_sandbox.sh /data/local/tmp/bridge_to_quickssh_sandbox.sh
%ADB% -s %TARGET_DEVICE% shell "chmod +x /data/local/tmp/*.sh"

echo [5/6] Executing Termux setup and opencode configuration...
%ADB% -s %TARGET_DEVICE% shell am start -n com.termux/.app.TermuxActivity
timeout /t 3 >nul
%ADB% -s %TARGET_DEVICE% shell "su -c 'cp /data/local/tmp/*.sh /data/data/com.termux/files/home/ && chmod +x /data/data/com.termux/files/home/*.sh && chown -R $(stat -c %u:%g /data/data/com.termux/files/home) /data/data/com.termux/files/home/*.sh'" 2>nul || %ADB% -s %TARGET_DEVICE% shell "cp /data/local/tmp/*.sh /data/data/com.termux/files/home/"

echo Running opencode setup in Termux...
%ADB% -s %TARGET_DEVICE% shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/termux_setup_opencode.sh" 2>nul || %ADB% -s %TARGET_DEVICE% shell "su -c '/data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/termux_setup_opencode.sh'"

echo [6/6] Bridging to QuickSSH sandbox and running verification...
%ADB% -s %TARGET_DEVICE% shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/bridge_to_quickssh_sandbox.sh" 2>nul || %ADB% -s %TARGET_DEVICE% shell "su -c '/data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/home/bridge_to_quickssh_sandbox.sh'"

echo ========================================================
echo  End-to-End Test Execution Finished!
echo ========================================================
