#!/usr/bin/env bash
#
# anvild macOS service manager (arch §3/§5, impl plan 6).
#   ./scripts/service.sh install     # install + load the LaunchAgent, build web, wire tailscale
#   ./scripts/service.sh uninstall   # bootout + remove plist/launcher (keeps state)
#   ./scripts/service.sh restart     # kickstart the service
#   ./scripts/service.sh status      # service state + /api/health
#   ./scripts/service.sh logs        # tail the daemon log
#
set -euo pipefail

LABEL="com.anvil.anvild"
PORT="${ANVIL_PORT:-7701}"
ANVILD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="$HOME/.local/bin"
LAUNCHER="$BIN_DIR/anvild-launch"
STATE_DIR="$HOME/.local/state/anvil"
CONFIG_ENV="$HOME/.config/anvil/env"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"

find_bun() {
  command -v bun 2>/dev/null && return 0
  [ -x "$HOME/.bun/bin/bun" ] && { echo "$HOME/.bun/bin/bun"; return 0; }
  return 1
}

wait_health() {
  for _ in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1 && return 0
    sleep 0.2
  done
  return 1
}

free_port() {
  pkill -f "anvild/src/main.ts" 2>/dev/null || true
  local pids
  pids="$(lsof -ti "tcp:$PORT" 2>/dev/null || true)"
  [ -n "$pids" ] && kill $pids 2>/dev/null || true
  sleep 0.5
}

do_install() {
  local bun; bun="$(find_bun)" || { echo "error: bun not found (looked on PATH and ~/.bun/bin)"; exit 1; }
  [ -f "$CONFIG_ENV" ] || {
    echo "error: missing $CONFIG_ENV"
    echo "  Run:  claude setup-token"
    echo "  Then: mkdir -p ~/.config/anvil && umask 077 && printf 'CLAUDE_CODE_OAUTH_TOKEN=%s\\n' '<token>' > $CONFIG_ENV"
    exit 1
  }
  mkdir -p "$BIN_DIR" "$STATE_DIR" "$(dirname "$PLIST")"

  echo "building web client…"
  ( cd "$ANVILD_DIR" && "$bun" run build:web >/dev/null )

  # launcher — sources the OAuth token, guarantees no metered key reaches the daemon (arch §3)
  cat > "$LAUNCHER" <<LAUNCH
#!/bin/sh
export PATH="\$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
set -a
[ -f "\$HOME/.config/anvil/env" ] && . "\$HOME/.config/anvil/env"
set +a
unset ANTHROPIC_API_KEY
unset ANTHROPIC_AUTH_TOKEN
export ANVIL_HOST=127.0.0.1
export ANVIL_PORT=$PORT
exec "$bun" run "$ANVILD_DIR/src/main.ts"
LAUNCH
  chmod 755 "$LAUNCHER"

  # LaunchAgent (no secrets in the plist — they come from the launcher)
  cat > "$PLIST" <<PLISTEOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$LAUNCHER</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>5</integer>
  <key>WorkingDirectory</key><string>$ANVILD_DIR</string>
  <key>StandardOutPath</key><string>$STATE_DIR/anvild.log</string>
  <key>StandardErrorPath</key><string>$STATE_DIR/anvild.error.log</string>
</dict>
</plist>
PLISTEOF

  free_port
  launchctl bootout "$DOMAIN" "$PLIST" 2>/dev/null || true
  launchctl bootstrap "$DOMAIN" "$PLIST"
  launchctl kickstart -k "$DOMAIN/$LABEL"

  if wait_health; then
    echo "healthy: $(curl -fsS "http://127.0.0.1:$PORT/api/health")"
  else
    echo "warning: not healthy yet — check $STATE_DIR/anvild.error.log"
  fi

  # tailscale serve (idempotent)
  if command -v tailscale >/dev/null 2>&1; then
    tailscale serve --bg --https="$PORT" "http://127.0.0.1:$PORT" >/dev/null 2>&1 || true
    local dns
    dns="$(tailscale status --json 2>/dev/null | "$bun" -e 'const s=JSON.parse(await Bun.stdin.text());process.stdout.write((s.Self?.DNSName||"").replace(/\.$/,""))' 2>/dev/null || true)"
    [ -n "$dns" ] && echo "URL: https://$dns:$PORT/"
  fi
  echo "installed $LABEL  (logs: $STATE_DIR/anvild.log)"
}

case "${1:-install}" in
  install)   do_install ;;
  uninstall) launchctl bootout "$DOMAIN" "$PLIST" 2>/dev/null || true; rm -f "$PLIST" "$LAUNCHER"; echo "removed $LABEL (state kept at $STATE_DIR)" ;;
  restart)   launchctl kickstart -k "$DOMAIN/$LABEL"; wait_health && echo "restarted, healthy" || echo "restarted (health pending)" ;;
  status)    launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -E 'state =|pid =' || echo "not loaded"; curl -fsS "http://127.0.0.1:$PORT/api/health" 2>/dev/null && echo || echo "no health" ;;
  logs)      tail -n 80 -f "$STATE_DIR/anvild.log" ;;
  *) echo "usage: service.sh {install|uninstall|restart|status|logs}"; exit 1 ;;
esac
