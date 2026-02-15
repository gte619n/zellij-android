#!/bin/bash
#
# ZellijConnect Session Status Server Uninstaller
#

set -e

INSTALL_DIR="/usr/local/bin"
HOOK_DIR="$HOME/.local/bin"
LAUNCHAGENT_DIR="$HOME/Library/LaunchAgents"
PLIST_FILE="$LAUNCHAGENT_DIR/com.zellijconnect.session-status-server.plist"

echo "=== ZellijConnect Session Status Server Uninstaller ==="
echo ""

# Stop and unload LaunchAgent
echo "[1/3] Stopping LaunchAgent..."
launchctl unload "$PLIST_FILE" 2>/dev/null || true
rm -f "$PLIST_FILE"
echo "      Removed LaunchAgent"

# Remove server script
echo "[2/3] Removing server script..."
sudo rm -f "$INSTALL_DIR/session-status-server.py"
echo "      Removed $INSTALL_DIR/session-status-server.py"

# Remove hook script
echo "[3/3] Removing hook script..."
rm -f "$HOOK_DIR/claude-status-hook.sh"
echo "      Removed $HOOK_DIR/claude-status-hook.sh"

echo ""
echo "=== Uninstall Complete ==="
echo ""
echo "Note: Claude Code hooks in ~/.claude/settings.json were NOT removed."
echo "      Remove them manually if needed."
echo ""
echo "Status files in ~/.claude-status/ were NOT removed."
echo "      Remove with: rm -rf ~/.claude-status"
