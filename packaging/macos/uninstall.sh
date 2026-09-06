#!/bin/bash
set -e

echo "============================================================"
echo "          GolosAI Voice Assistant - macOS Uninstaller       "
echo "============================================================"

rm -rf "/Applications/GolosAI.app" "$HOME/Applications/GolosAI.app"
rm -f "$HOME/Library/LaunchAgents/su.kamil.dev.golos.plist"

echo "[OK] GolosAI application removed successfully from macOS."
