#!/bin/bash
set -e

echo "============================================================"
echo "          GolosAI Voice Assistant - Linux Uninstaller       "
echo "============================================================"

INSTALL_DIR="$HOME/.local/share/golos-ai"
BIN_DIR="$HOME/.local/bin/golos-ai"
DESKTOP_DIR="$HOME/.local/share/applications/golos-ai.desktop"
AUTOSTART_DIR="$HOME/.config/autostart/golos-ai.desktop"

rm -rf "$INSTALL_DIR"
rm -f "$BIN_DIR" "$DESKTOP_DIR" "$AUTOSTART_DIR"

if [ -f /etc/udev/rules.d/99-golos-input.rules ]; then
    echo "Removing udev input rules..."
    sudo rm -f /etc/udev/rules.d/99-golos-input.rules 2>/dev/null || true
    sudo udevadm control --reload-rules 2>/dev/null || true
fi

echo "[OK] GolosAI and all system autostart integrations removed successfully."
