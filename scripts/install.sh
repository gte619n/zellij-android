#!/bin/bash
#
# ZellijConnect Session Status Server Installer
#
# Installs the session status server and Claude Code hooks on macOS.
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/usr/local/bin"
HOOK_DIR="$HOME/.local/bin"
LAUNCHAGENT_DIR="$HOME/Library/LaunchAgents"
CLAUDE_SETTINGS="$HOME/.claude/settings.json"

echo "=== ZellijConnect Session Status Server Installer ==="
echo ""

# Install server script
echo "[1/4] Installing session-status-server.py..."
sudo mkdir -p "$INSTALL_DIR"
sudo cp "$SCRIPT_DIR/session-status-server.py" "$INSTALL_DIR/"
sudo chmod +x "$INSTALL_DIR/session-status-server.py"
echo "      Installed to $INSTALL_DIR/session-status-server.py"

# Install hook script
echo "[2/4] Installing claude-status-hook.sh..."
mkdir -p "$HOOK_DIR"
cp "$SCRIPT_DIR/claude-status-hook.sh" "$HOOK_DIR/"
chmod +x "$HOOK_DIR/claude-status-hook.sh"
echo "      Installed to $HOOK_DIR/claude-status-hook.sh"

# Install LaunchAgent
echo "[3/4] Installing LaunchAgent..."
mkdir -p "$LAUNCHAGENT_DIR"
PLIST_FILE="$LAUNCHAGENT_DIR/com.zellijconnect.session-status-server.plist"

# Unload if already loaded
launchctl unload "$PLIST_FILE" 2>/dev/null || true

# Create plist with correct path
sed "s|SESSION_SERVER_SCRIPT_PATH|$INSTALL_DIR/session-status-server.py|g" \
    "$SCRIPT_DIR/com.zellijconnect.session-status-server.plist" \
    > "$PLIST_FILE"

launchctl load "$PLIST_FILE"
echo "      Installed and started LaunchAgent"

# Check if server is running
sleep 1
if curl -s http://localhost:7601/api/health > /dev/null 2>&1; then
    echo "      Server is running on port 7601"
else
    echo "      Warning: Server may not be running. Check logs at /tmp/session-status-server.error.log"
fi

# Configure Claude Code hooks
echo "[4/4] Configuring Claude Code hooks..."
mkdir -p "$(dirname "$CLAUDE_SETTINGS")"

if [ -f "$CLAUDE_SETTINGS" ]; then
    # Backup existing settings
    cp "$CLAUDE_SETTINGS" "$CLAUDE_SETTINGS.backup.$(date +%Y%m%d%H%M%S)"
    echo "      Backed up existing settings"

    # Check if hooks already configured
    if grep -q "claude-status-hook" "$CLAUDE_SETTINGS" 2>/dev/null; then
        echo "      Claude hooks already configured in $CLAUDE_SETTINGS"
    else
        echo "      NOTE: Please manually add hooks to $CLAUDE_SETTINGS"
        echo "      See README.md for the hooks configuration JSON"
    fi
else
    # Create new settings file with hooks
    cat > "$CLAUDE_SETTINGS" << 'EOF'
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [{
          "type": "command",
          "command": "~/.local/bin/claude-status-hook.sh session-start"
        }]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "*",
        "hooks": [{
          "type": "command",
          "command": "~/.local/bin/claude-status-hook.sh pre-tool"
        }]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "*",
        "hooks": [{
          "type": "command",
          "command": "~/.local/bin/claude-status-hook.sh post-tool"
        }]
      }
    ],
    "Notification": [
      {
        "hooks": [{
          "type": "command",
          "command": "~/.local/bin/claude-status-hook.sh notification"
        }]
      }
    ],
    "Stop": [
      {
        "hooks": [{
          "type": "command",
          "command": "~/.local/bin/claude-status-hook.sh stop"
        }]
      }
    ]
  }
}
EOF
    echo "      Created $CLAUDE_SETTINGS with hooks"
fi

echo ""
echo "=== Installation Complete ==="
echo ""
echo "Server running at: http://localhost:7601"
echo ""
echo "Test with:"
echo "  curl http://localhost:7601/api/sessions | jq"
echo ""
echo "View logs:"
echo "  tail -f /tmp/session-status-server.log"
echo ""
echo "Don't forget to add Zellij scroll keybindings!"
echo "See README.md for ~/.config/zellij/config.kdl configuration."
