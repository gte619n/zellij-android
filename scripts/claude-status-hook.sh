#!/bin/bash
#
# Claude Code Status Hook
#
# This script is called by Claude Code hooks to update session status.
# It reads JSON from stdin and writes status to ~/.claude-status/{session}.json
#
# Usage (configured in ~/.claude/settings.json):
#   claude-status-hook.sh <event-type>
#
# Event types: session-start, pre-tool, post-tool, notification, stop
#

set -e

STATUS_DIR="$HOME/.claude-status"
CWD_MAP_FILE="$HOME/.zellij-session-cwd.json"

# Create status directory if needed
mkdir -p "$STATUS_DIR"

# Get event type from argument
EVENT_TYPE="${1:-unknown}"

# Read JSON input from stdin
INPUT=$(cat)

# Get session name from ZELLIJ_SESSION_NAME env var, or use "default"
SESSION_NAME="${ZELLIJ_SESSION_NAME:-default}"

# Get current working directory
CURRENT_CWD="$(pwd)"

# Status file for this session
STATUS_FILE="$STATUS_DIR/$SESSION_NAME.json"

# Helper to get current ISO timestamp
get_timestamp() {
    date -u +"%Y-%m-%dT%H:%M:%SZ"
}

# Helper to extract field from JSON input
json_field() {
    echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$1', ''))" 2>/dev/null || echo ""
}

# Update the session-to-cwd mapping
update_cwd_map() {
    if [ -f "$CWD_MAP_FILE" ]; then
        # Update existing map
        python3 -c "
import json
import sys

try:
    with open('$CWD_MAP_FILE', 'r') as f:
        data = json.load(f)
except:
    data = {}

data['$SESSION_NAME'] = '$CURRENT_CWD'

with open('$CWD_MAP_FILE', 'w') as f:
    json.dump(data, f, indent=2)
" 2>/dev/null || true
    else
        # Create new map
        echo "{\"$SESSION_NAME\": \"$CURRENT_CWD\"}" > "$CWD_MAP_FILE"
    fi
}

# Write status based on event type
case "$EVENT_TYPE" in
    session-start)
        update_cwd_map
        cat > "$STATUS_FILE" << EOF
{
  "status": "idle",
  "activity": "Session started",
  "tool": null,
  "timestamp": "$(get_timestamp)"
}
EOF
        ;;

    pre-tool)
        TOOL_NAME=$(json_field "tool_name")
        TOOL_INPUT=$(json_field "tool_input" | head -c 100)
        update_cwd_map
        cat > "$STATUS_FILE" << EOF
{
  "status": "working",
  "activity": "Using $TOOL_NAME",
  "tool": "$TOOL_NAME",
  "timestamp": "$(get_timestamp)"
}
EOF
        ;;

    post-tool)
        TOOL_NAME=$(json_field "tool_name")
        update_cwd_map
        cat > "$STATUS_FILE" << EOF
{
  "status": "working",
  "activity": "Completed $TOOL_NAME",
  "tool": "$TOOL_NAME",
  "timestamp": "$(get_timestamp)"
}
EOF
        ;;

    notification)
        update_cwd_map
        cat > "$STATUS_FILE" << EOF
{
  "status": "waiting",
  "activity": "Waiting for input",
  "tool": null,
  "timestamp": "$(get_timestamp)"
}
EOF
        ;;

    stop)
        update_cwd_map
        cat > "$STATUS_FILE" << EOF
{
  "status": "idle",
  "activity": "Finished",
  "tool": null,
  "timestamp": "$(get_timestamp)"
}
EOF
        ;;

    *)
        # Unknown event, just update timestamp
        update_cwd_map
        cat > "$STATUS_FILE" << EOF
{
  "status": "unknown",
  "activity": "Unknown event: $EVENT_TYPE",
  "tool": null,
  "timestamp": "$(get_timestamp)"
}
EOF
        ;;
esac

# Always exit successfully so we don't block Claude
exit 0
