#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "=========================================="
echo " [Step 1/4] Updating Termux & Installing Base Packages"
echo "=========================================="
pkg update -y || true
pkg install -y nodejs-lts git curl jq openssh

echo "=========================================="
echo " [Step 2/4] Installing opencode CLI in Termux"
echo "=========================================="
npm install -g opencode-ai || npm install -g @opencode/cli || npm install -g opencode

echo "=========================================="
echo " [Step 3/4] Configuring opencode with Gemini 3.8"
echo "=========================================="
mkdir -p "$HOME/.config/opencode"
cat << 'EOF' > "$HOME/.config/opencode/opencode.json"
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "google": {
      "model": "gemini-3.8-flash-high"
    }
  },
  "default_model": "google/gemini-3.8-flash-high"
}
EOF

echo "=========================================="
echo " [Step 4/4] Verifying Termux opencode"
echo "=========================================="
opencode --version || which opencode
echo "[SUCCESS] Termux opencode installation and configuration completed."
