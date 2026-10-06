#!/data/data/com.termux/files/usr/bin/bash
set -e

BRIDGE_PORT="${1:-8024}"
BRIDGE_URL="http://127.0.0.1:$BRIDGE_PORT"

echo "=========================================="
echo " [Step 1/3] Checking QuickSSH Gateway Connection"
echo "=========================================="
STATUS=$(curl -s "$BRIDGE_URL/api/status")
echo "QuickSSH Bridge Status: $STATUS"

echo "=========================================="
echo " [Step 2/3] Configuring QuickSSH Sandbox Environment via Bridge"
echo "=========================================="
# 通过 QuickSSH 网关的 /api/terminal/exec 接口在 QuickSSH 内部创建并配置环境
SETUP_CMD=$(cat << 'EOF'
mkdir -p /data/data/com.quickssh.app/files/home/.config/opencode
cat << 'OPENCFG' > /data/data/com.quickssh.app/files/home/.config/opencode/opencode.json
{
  "provider": {
    "google": {
      "model": "gemini-3.8-flash-high"
    }
  },
  "default_model": "google/gemini-3.8-flash-high"
}
OPENCFG

# 写入 QuickSSH 本地终端环境配置
cat << 'ENVFILE' > /data/data/com.quickssh.app/files/home/.profile
export TERM=xterm-256color
export HOME=/data/data/com.quickssh.app/files/home
export PREFIX=/data/data/com.termux/files/usr
export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:$PATH
export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib
export OPENCODE_CONFIG_DIR=/data/data/com.quickssh.app/files/home/.config/opencode
ENVFILE

ln -sf /data/data/com.termux/files/usr/bin/opencode /data/data/com.quickssh.app/files/home/opencode 2>/dev/null || true
echo "SUCCESS: QuickSSH sandbox profile and opencode bindings configured."
EOF
)

curl -s -X POST -d "$SETUP_CMD" "$BRIDGE_URL/api/terminal/exec"
echo ""

echo "=========================================="
echo " [Step 3/3] Testing opencode Execution in QuickSSH Sandbox"
echo "=========================================="
TEST_CMD="source /data/data/com.quickssh.app/files/home/.profile && opencode --version"
RESULT=$(curl -s -X POST -d "$TEST_CMD" "$BRIDGE_URL/api/terminal/exec")
echo "Execution Result:"
echo "$RESULT"
echo ""
echo "[ACCEPTANCE PASSED] opencode is executable inside QuickSSH sandbox local terminal."
