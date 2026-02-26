# Session Status Server

A lightweight HTTP server that provides session status information for the ZellijConnect Android app.

## Features

- Lists active Zellij sessions with working directory
- Tracks Claude Code activity status per session (with description)
- Reports git branch status (uncommitted changes, unpushed commits, worktree detection)
- Supports session deletion with optional worktree/branch cleanup
- Auto-starts via macOS LaunchAgent or Linux systemd user service
- **HTTPS support via Tailscale** for secure remote access

## Components

| File | Description |
|------|-------------|
| `session-status-server.py` | Main HTTP server (port 7601) |
| `claude-status-hook.sh` | Claude Code hook script |
| `install.sh` | Cross-platform installer (macOS + Linux) |
| `restart-server.sh` | Restart the running service |
| `uninstall.sh` | Remove all installed components |
| `com.zellijconnect.session-status-server.plist` | macOS LaunchAgent template |

## Quick Install

```bash
cd scripts/
./install.sh
```

No `sudo` required. Installs everything to `~/.local/bin`.

```bash
# Update scripts only (keep service running, then restart)
./install.sh --update

# Uninstall
./install.sh --uninstall
```

## Prerequisites

### macOS
```bash
brew install python3 git zellij
# Optional: for HTTPS remote access
brew install tailscale
```

### Linux (Debian/Ubuntu)
```bash
sudo apt install python3 git curl

# Install Zellij (pick one):
# Via cargo:
cargo install zellij
# Via binary release:
curl -L https://github.com/zellij-org/zellij/releases/latest/download/zellij-x86_64-unknown-linux-musl.tar.gz \
    | tar xz && sudo mv zellij /usr/local/bin/

# Optional: for HTTPS remote access
# https://tailscale.com/download/linux
```

## API Reference

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
        "description": "Add dark mode support to the dashboard",
        "tool": "Write",
        "timestamp": "2024-02-15T14:22:00Z"
      },
      "git": {
        "branch": "feature/dark-mode",
        "mergedToDev": false,
        "remoteBranchExists": true,
        "lastCommit": "Add color tokens",
        "hasUncommittedChanges": true,
        "unpushedCommitCount": 2,
        "hasWorktree": false
      }
    }
  ],
  "timestamp": "2024-02-15T14:23:00Z"
}
```

Claude `status` values: `idle`, `working`, `waiting`, `stale`, `unknown`

### `GET /api/health`

```json
{"status": "ok"}
```

### `DELETE /api/sessions/{name}`

Kill a Zellij session with optional cleanup.

Query params:
- `deleteWorktree=true` — remove git worktree (if session is in a worktree)
- `deleteBranch=true` — delete the git branch (requires `deleteWorktree=true`)

```bash
# Kill session only
curl -X DELETE http://localhost:7601/api/sessions/my-project

# Kill and clean up worktree + branch
curl -X DELETE "http://localhost:7601/api/sessions/my-project?deleteWorktree=true&deleteBranch=true"
```

Response:
```json
{"success": true, "killed": "my-project", "worktreeRemoved": true, "branchDeleted": true}
```

## Manual Installation Steps

The installer handles all of this automatically. These steps are for reference.

### 1. Install scripts

```bash
mkdir -p ~/.local/bin
cp session-status-server.py ~/.local/bin/ && chmod +x ~/.local/bin/session-status-server.py
cp claude-status-hook.sh ~/.local/bin/     && chmod +x ~/.local/bin/claude-status-hook.sh
```

### 2. Configure Claude Code hooks

Add to `~/.claude/settings.json`:

```json
{
  "hooks": {
    "SessionStart": [
      {"hooks": [{"type": "command", "command": "~/.local/bin/claude-status-hook.sh session-start"}]}
    ],
    "PreToolUse": [
      {"matcher": "*", "hooks": [{"type": "command", "command": "~/.local/bin/claude-status-hook.sh pre-tool"}]}
    ],
    "PostToolUse": [
      {"matcher": "*", "hooks": [{"type": "command", "command": "~/.local/bin/claude-status-hook.sh post-tool"}]}
    ],
    "Notification": [
      {"hooks": [{"type": "command", "command": "~/.local/bin/claude-status-hook.sh notification"}]}
    ],
    "Stop": [
      {"hooks": [{"type": "command", "command": "~/.local/bin/claude-status-hook.sh stop"}]}
    ]
  }
}
```

### 3a. Auto-start on macOS (LaunchAgent)

```bash
PYTHON3=$(which python3)
SCRIPT_PATH="$HOME/.local/bin/session-status-server.py"

sed -e "s|PYTHON3_PATH|${PYTHON3}|g" \
    -e "s|SESSION_SERVER_SCRIPT_PATH|${SCRIPT_PATH}|g" \
    com.zellijconnect.session-status-server.plist \
    > ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist

launchctl load ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist
curl http://localhost:7601/api/health
```

### 3b. Auto-start on Linux (systemd user service)

```bash
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/session-status-server.service << EOF
[Unit]
Description=ZellijConnect Session Status Server
After=network.target

[Service]
Type=simple
ExecStart=$(which python3) $HOME/.local/bin/session-status-server.py
Restart=on-failure
RestartSec=5
Environment=SESSION_SERVER_PORT=7601

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now session-status-server
curl http://localhost:7601/api/health
```

### 4. Tailscale HTTPS (optional)

```bash
tailscale serve --bg --https 7601 http://localhost:7601

# Your API will be available at:
# https://your-tailscale-hostname:7601/api/sessions
```

### 5. Zellij scroll keybindings

Add to `~/.config/zellij/config.kdl`:

```kdl
keybinds {
    shared {
        bind "Ctrl Shift Alt k" { ScrollUp; }
        bind "Ctrl Shift Alt j" { ScrollDown; }
    }
}
```

## Service Management

### macOS

```bash
# Start
launchctl load ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist

# Stop
launchctl unload ~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist

# Restart (via script)
./restart-server.sh

# Logs
tail -f /tmp/session-status-server.log
tail -f /tmp/session-status-server.error.log
```

### Linux

```bash
# Start / Stop / Restart
systemctl --user start session-status-server
systemctl --user stop session-status-server
systemctl --user restart session-status-server

# Status
systemctl --user status session-status-server

# Logs
journalctl --user -u session-status-server -f
```

### Manual (no service)

```bash
python3 ~/.local/bin/session-status-server.py
```

## Testing

```bash
# Health check
curl http://localhost:7601/api/health

# List sessions
curl http://localhost:7601/api/sessions | python3 -m json.tool

# Manually create a test status file
mkdir -p ~/.claude-status
echo '{"status":"working","activity":"Test","tool":"Write","timestamp":"2024-01-01T00:00:00Z"}' \
    > ~/.claude-status/test-session.json
```

## Troubleshooting

### Server won't start

```bash
# macOS
cat /tmp/session-status-server.error.log

# Linux
journalctl --user -u session-status-server -n 30
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

2. Check `ZELLIJ_SESSION_NAME` is set (must be inside a Zellij session):
   ```bash
   echo $ZELLIJ_SESSION_NAME
   ```

3. Test hook manually:
   ```bash
   echo '{}' | ~/.local/bin/claude-status-hook.sh session-start
   cat ~/.claude-status/$ZELLIJ_SESSION_NAME.json
   ```

### Sessions not showing working directory

The hook writes `~/.zellij-session-cwd.json` on every Claude tool call. Claude Code must be run at least once inside the Zellij session to populate the mapping.

### Tailscale HTTPS issues

```bash
# View current config
tailscale serve status

# Reset and reconfigure
tailscale serve --https=7601 off
tailscale serve --bg --https 7601 http://localhost:7601
```

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `SESSION_SERVER_PORT` | `7601` | HTTP server port |

## File Locations

| Path | Purpose |
|------|---------|
| `~/.local/bin/session-status-server.py` | Server script |
| `~/.local/bin/claude-status-hook.sh` | Claude Code hook |
| `~/.claude-status/*.json` | Claude status per session |
| `~/.zellij-session-cwd.json` | Session name → working directory map |
| `~/Library/LaunchAgents/com.zellijconnect.session-status-server.plist` | macOS service (auto-start) |
| `~/.config/systemd/user/session-status-server.service` | Linux service (auto-start) |
| `/tmp/session-status-server.log` | Server stdout (macOS) |
| `/tmp/session-status-server.error.log` | Server stderr (macOS) |
