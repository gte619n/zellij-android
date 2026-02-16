# Session Status Server

A lightweight HTTP server that provides session status information for the ZellijConnect Android app.

## Features

- Lists active Zellij sessions
- Tracks Claude Code activity status per session
- Reports git branch status (merged to dev, remote branch exists)
- Auto-starts on macOS via LaunchAgent
- **HTTPS support via Tailscale** for secure remote access

## Components

| File | Description |
|------|-------------|
| `session-status-server.py` | Main HTTP server (port 7601) |
| `claude-status-hook.sh` | Claude Code hook script |
| `com.zellijconnect.session-status-server.plist` | macOS LaunchAgent |

## API Endpoints

### `GET /api/sessions`

Returns all active Zellij sessions with status:

```json
{
  "sessions": [
    {
      "name": "my-project",
      "workingDirectory": "/Users/me/projects/my-project",
      "claude": {
        "status": "working",
        "activity": "Using Write",
        "tool": "Write",
        "timestamp": "2024-02-15T14:22:00Z"
      },
      "git": {
        "branch": "feature/new-thing",
        "mergedToDev": false,
        "remoteBranchExists": true,
        "lastCommit": "Add new feature"
      }
    }
  ],
  "timestamp": "2024-02-15T14:23:00Z"
}
```

### `GET /api/health`

Health check endpoint:

```json
{"status": "ok"}
```

## Installation

### 1. Install the Server Script

```bash
# Copy to a permanent location
sudo mkdir -p /usr/local/bin
sudo cp session-status-server.py /usr/local/bin/
sudo chmod +x /usr/local/bin/session-status-server.py
```

### 2. Install the Claude Status Hook

```bash
# Copy hook script
mkdir -p ~/.local/bin
cp claude-status-hook.sh ~/.local/bin/
chmod +x ~/.local/bin/claude-status-hook.sh
```

### 3. Configure Claude Code Hooks

Add to `~/.claude/settings.json`:

```json
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
```

Or use the Claude Code CLI:
```bash
claude config hooks
```

### 4. Set Up Auto-Start (macOS)

```bash
# Create the plist with correct path
SCRIPT_PATH="/usr/local/bin/session-status-server.py"
sed "s|SESSION_SERVER_SCRIPT_PATH|$SCRIPT_PATH|g" \
    com.zellijconnect.session-status-server.plist \
    > ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist

# Load and start the service
launchctl load ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist

# Verify it's running
curl http://localhost:7601/api/health
```

### 5. Configure Tailscale HTTPS (Automatic)

The installer automatically runs:
```bash
tailscale serve --bg --https 7601 http://localhost:7601
```

To manually configure or verify:
```bash
# View current Tailscale serve config
tailscale serve status

# Manually add HTTPS proxy (if needed)
tailscale serve --bg --https 7601 http://localhost:7601

# Remove HTTPS proxy
tailscale serve --https=7601 off
```

Your session API will be available at:
```
https://your-tailscale-hostname:7601/api/sessions
```

### 6. Configure Zellij Scroll Keybindings

Add to `~/.config/zellij/config.kdl`:

```kdl
keybinds {
    shared {
        bind "Ctrl Shift Alt k" { ScrollUp; }
        bind "Ctrl Shift Alt j" { ScrollDown; }
    }
}
```

## Manual Start/Stop

```bash
# Start manually (HTTP only, no Tailscale)
python3 /usr/local/bin/session-status-server.py

# Stop the LaunchAgent
launchctl unload ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist

# Start the LaunchAgent
launchctl load ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist

# View logs
tail -f /tmp/session-status-server.log
tail -f /tmp/session-status-server.error.log

# Restart Tailscale serve (if connection issues)
tailscale serve --https=7601 off
tailscale serve --bg --https 7601 http://localhost:7601
```

## Testing

```bash
# Check server is running
curl http://localhost:7601/api/health

# Get sessions
curl http://localhost:7601/api/sessions | jq

# Manually create a test status file
mkdir -p ~/.claude-status
echo '{"status":"working","activity":"Test","timestamp":"2024-01-01T00:00:00Z"}' \
    > ~/.claude-status/test-session.json
```

## Troubleshooting

### Server won't start

Check logs:
```bash
cat /tmp/session-status-server.error.log
```

Check if port is in use:
```bash
lsof -i :7601
```

### Claude status not updating

1. Verify hook script is executable:
   ```bash
   ls -la ~/.local/bin/claude-status-hook.sh
   ```

2. Check ZELLIJ_SESSION_NAME is set in your Zellij session:
   ```bash
   echo $ZELLIJ_SESSION_NAME
   ```

3. Test hook manually:
   ```bash
   echo '{}' | ~/.local/bin/claude-status-hook.sh session-start
   cat ~/.claude-status/$ZELLIJ_SESSION_NAME.json
   ```

### Sessions not showing working directory

The hook script updates `~/.zellij-session-cwd.json` with session-to-directory mappings.
This requires Claude Code to be run at least once in each session for the mapping to be created.

## Configuration

Environment variables for the server:

| Variable | Default | Description |
|----------|---------|-------------|
| `SESSION_SERVER_PORT` | `7601` | HTTP server port |

## File Locations

| File | Purpose |
|------|---------|
| `~/.claude-status/*.json` | Claude status per session |
| `~/.zellij-session-cwd.json` | Session name to CWD mapping |
| `/tmp/session-status-server.log` | Server stdout log |
| `/tmp/session-status-server.error.log` | Server stderr log |
