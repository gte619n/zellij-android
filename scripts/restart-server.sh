#!/bin/bash
set -e

echo "=== Restarting Session Status Server ==="
echo ""

# Kill any process using port 7601
echo "1. Killing any process on port 7601..."
sudo lsof -i :7601 -t 2>/dev/null | xargs sudo kill -9 2>/dev/null || echo "   No process found on port 7601"

# Update the server script with EXITED filter
echo "2. Installing updated server script..."
sudo cp /tmp/session-status-server.py.new /usr/local/bin/session-status-server.py
sudo chmod +x /usr/local/bin/session-status-server.py

# Verify the filter is in place
if grep -q "EXITED" /usr/local/bin/session-status-server.py; then
    echo "   ✓ EXITED filter installed"
else
    echo "   ✗ WARNING: Filter not found in script"
fi

# Restart LaunchAgent
echo "3. Restarting LaunchAgent..."
launchctl unload ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist 2>/dev/null || true
sleep 1
launchctl load ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist

echo "4. Waiting for server to start..."
sleep 3

# Test the server
echo "5. Testing server..."
if curl -s http://localhost:7601/api/health > /dev/null 2>&1; then
    echo "   ✓ Server is running"
    
    echo ""
    echo "Active sessions:"
    curl -s http://localhost:7601/api/sessions | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(f'  Total: {len(d[\"sessions\"])} (should be ~7, not 20)')
for s in d['sessions']:
    print(f'    - {s[\"name\"]}')
" 2>/dev/null || echo "   Error parsing sessions"
    
    echo ""
    echo "HTTPS endpoint:"
    HOSTNAME=$(tailscale status --json 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['Self']['DNSName'])" 2>/dev/null || echo "unknown")
    if [ "$HOSTNAME" != "unknown" ]; then
        echo "  https://${HOSTNAME}7601/api/sessions"
    else
        echo "  (Tailscale hostname not detected)"
    fi
else
    echo "   ✗ Server failed to start"
    echo "   Check logs: tail /tmp/session-status-server.error.log"
fi

echo ""
echo "Done!"
