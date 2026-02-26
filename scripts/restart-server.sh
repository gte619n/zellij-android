#!/bin/bash
#
# ZellijConnect Session Status Server - Restart Utility
#
# Restarts the session status server on macOS (LaunchAgent) or Linux (systemd).
# To also update scripts from the repo, use: ./install.sh --update
#

set -euo pipefail

SERVICE_PORT=7601

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "  ${GREEN}✓${NC} $*"; }
warn() { echo -e "  ${YELLOW}⚠${NC}  $*"; }
err()  { echo -e "  ${RED}✗${NC} $*"; }

echo "=== Restarting Session Status Server ==="

OS=$(uname -s)

# Kill any orphan processes on the port
if lsof -i ":$SERVICE_PORT" -t >/dev/null 2>&1; then
    echo ""
    echo "1. Killing orphan process on port $SERVICE_PORT..."
    lsof -i ":$SERVICE_PORT" -t | xargs kill -9 2>/dev/null || true
    ok "Killed"
fi

echo ""
echo "2. Restarting service..."

if [ "$OS" = "Darwin" ]; then
    PLIST="$HOME/Library/LaunchAgents/com.zellijconnect.session-status-server.plist"
    if [ ! -f "$PLIST" ]; then
        err "LaunchAgent not installed at $PLIST"
        echo "   Run ./install.sh to install first."
        exit 1
    fi
    launchctl unload "$PLIST" 2>/dev/null || true
    sleep 1
    launchctl load "$PLIST"

elif [ "$OS" = "Linux" ]; then
    if ! systemctl --user is-enabled session-status-server >/dev/null 2>&1; then
        err "systemd service not installed."
        echo "   Run ./install.sh to install first."
        exit 1
    fi
    systemctl --user restart session-status-server
fi

echo ""
echo "3. Waiting for server to start..."
sleep 2

if curl -s "http://localhost:$SERVICE_PORT/api/health" >/dev/null 2>&1; then
    ok "Server is running on port $SERVICE_PORT"

    SESSION_COUNT=$(curl -s "http://localhost:$SERVICE_PORT/api/sessions" | \
        python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d['sessions']))" 2>/dev/null || echo "?")
    ok "Active sessions: $SESSION_COUNT"

    # Show Tailscale HTTPS endpoint if available
    if command -v tailscale >/dev/null 2>&1; then
        HOSTNAME=$(tailscale status --json 2>/dev/null | \
            python3 -c "import sys,json; print(json.load(sys.stdin)['Self']['DNSName'])" 2>/dev/null || echo "")
        if [ -n "$HOSTNAME" ]; then
            echo ""
            echo "  HTTPS: https://${HOSTNAME}${SERVICE_PORT}/api/sessions"
        fi
    fi
else
    err "Server failed to start"
    if [ "$OS" = "Darwin" ]; then
        echo "   Check logs: tail /tmp/session-status-server.error.log"
    else
        echo "   Check logs: journalctl --user -u session-status-server -n 30"
    fi
    exit 1
fi

echo ""
echo "Done! To update scripts from repo: ./install.sh --update"
