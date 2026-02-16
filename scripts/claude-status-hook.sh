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

# Extract session description from transcript (first human message, truncated)
# Caches in a separate file so we only parse the transcript once per session
get_description() {
    local desc_file="$STATUS_DIR/$SESSION_NAME.desc"
    # Return cached description if it exists
    if [ -f "$desc_file" ]; then
        cat "$desc_file"
        return
    fi
    # Parse transcript for first human message
    local transcript
    transcript=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('transcript_path',''))" 2>/dev/null || echo "")
    if [ -n "$transcript" ] && [ -f "$transcript" ]; then
        local desc
        desc=$(python3 -c "
import json, sys
with open('$transcript') as f:
    for line in f:
        try:
            msg = json.loads(line.strip())
        except:
            continue
        if msg.get('type') == 'human':
            content = msg.get('message', {}).get('content', '')
            if isinstance(content, list):
                # Content can be a list of blocks
                for block in content:
                    if isinstance(block, dict) and block.get('type') == 'text':
                        content = block['text']
                        break
                    elif isinstance(block, str):
                        content = block
                        break
                else:
                    content = ''
            # Clean up: first line only, strip whitespace, truncate
            content = content.strip().split('\n')[0][:120]
            if content:
                print(content)
                break
" 2>/dev/null || echo "")
        if [ -n "$desc" ]; then
            echo "$desc" > "$desc_file"
            echo "$desc"
            return
        fi
    fi
    echo ""
}

# Read existing description from status file (preserves across events)
read_cached_description() {
    if [ -f "$STATUS_FILE" ]; then
        python3 -c "
import json, sys
try:
    with open('$STATUS_FILE') as f:
        d = json.load(f)
    desc = d.get('description', '')
    if desc:
        print(desc)
except:
    pass
" 2>/dev/null || echo ""
    else
        echo ""
    fi
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

# Helper to write status JSON with description
write_status() {
    local status="$1"
    local activity="$2"
    local tool="$3"
    local desc="$4"

    # Escape double quotes in description for JSON safety
    desc=$(echo "$desc" | sed 's/"/\\"/g')

    if [ -n "$desc" ]; then
        cat > "$STATUS_FILE" << EOF
{
  "status": "$status",
  "activity": "$activity",
  "description": "$desc",
  "tool": $tool,
  "timestamp": "$(get_timestamp)"
}
EOF
    else
        cat > "$STATUS_FILE" << EOF
{
  "status": "$status",
  "activity": "$activity",
  "description": null,
  "tool": $tool,
  "timestamp": "$(get_timestamp)"
}
EOF
    fi
}

# Write status based on event type
case "$EVENT_TYPE" in
    session-start)
        update_cwd_map
        DESC=$(get_description)
        write_status "idle" "Session started" "null" "$DESC"
        ;;

    pre-tool)
        TOOL_NAME=$(json_field "tool_name")
        update_cwd_map
        # On first tool use, try to extract description if we don't have one yet
        DESC=$(read_cached_description)
        if [ -z "$DESC" ]; then
            DESC=$(get_description)
        fi
        write_status "working" "Using $TOOL_NAME" "\"$TOOL_NAME\"" "$DESC"
        ;;

    post-tool)
        TOOL_NAME=$(json_field "tool_name")
        update_cwd_map
        DESC=$(read_cached_description)
        write_status "working" "Completed $TOOL_NAME" "\"$TOOL_NAME\"" "$DESC"
        ;;

    notification)
        update_cwd_map
        DESC=$(read_cached_description)
        write_status "waiting" "Waiting for input" "null" "$DESC"
        ;;

    stop)
        update_cwd_map
        DESC=$(read_cached_description)
        write_status "idle" "Finished" "null" "$DESC"
        # Clean up description cache on stop
        rm -f "$STATUS_DIR/$SESSION_NAME.desc"
        ;;

    *)
        # Unknown event, just update timestamp
        update_cwd_map
        DESC=$(read_cached_description)
        write_status "unknown" "Unknown event: $EVENT_TYPE" "null" "$DESC"
        ;;
esac

# Always exit successfully so we don't block Claude
exit 0
