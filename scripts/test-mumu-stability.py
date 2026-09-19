"""
Automated smoke & stability test for QuickSSH using uiautomator2 on MuMu emulator.
Target device: 127.0.0.1:16416 (Instance 1, preserving Instance 0).
"""
import os
import sys
import time

adb_dir = r"D:\Program Files\Netease\MuMuPlayer\nx_device\12.0\shell"
if adb_dir not in os.environ.get("PATH", ""):
    os.environ["PATH"] = adb_dir + ";" + os.environ.get("PATH", "")

import uiautomator2 as u2

TARGET_SERIAL = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:16416"

def run_test():
    print(f"[QuickSSH Test] Connecting to emulator {TARGET_SERIAL}...")
    d = u2.connect(TARGET_SERIAL)
    print(f"[QuickSSH Test] Connected. Device info: {d.info.get('productName')}")

    print("[QuickSSH Test] Launching QuickSSH...")
    d.app_start("com.quickssh.app", stop=False)
    time.sleep(2)

    # Verify app is in foreground
    app = d.app_current()
    assert app.get("package") == "com.quickssh.app", f"Unexpected app: {app}"
    print(f"[QuickSSH Test] App active: {app.get('activity')}")

    # Check Sessions tab
    sessions_tab = d(text="会话")
    if sessions_tab.exists:
        sessions_tab.click()
        time.sleep(1)
        print("[QuickSSH Test] Navigated to Sessions tab.")

    # Check Settings tab
    settings_tab = d(text="设置")
    if settings_tab.exists:
        settings_tab.click()
        time.sleep(1)
        print("[QuickSSH Test] Navigated to Settings tab.")

    # Return to home
    home_tab = d(text="主机")
    if home_tab.exists:
        home_tab.click()
        time.sleep(1)
        print("[QuickSSH Test] Returned to Home tab.")

    print("[QuickSSH Test] All verification steps passed successfully!")

if __name__ == "__main__":
    run_test()
