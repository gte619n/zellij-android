#!/usr/bin/env python3
"""
Zellij Session Status Server

Provides a REST API for querying Zellij session information, Claude Code status,
and git branch status. Designed to work with the ZellijConnect Android app.

Endpoints:
  GET /api/sessions - List all sessions with status
  GET /api/health   - Health check

Binds to port 7601 by default.
"""

import json
import os
import subprocess
import http.server
import socketserver
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse
import threading
import time

# Configuration
PORT = int(os.environ.get('SESSION_SERVER_PORT', 7601))
STATUS_DIR = Path.home() / '.claude-status'
SESSION_CWD_MAP_FILE = Path.home() / '.zellij-session-cwd.json'
CACHE_TTL_SECONDS = 2

# Cache for session data
_cache = {
    'data': None,
    'timestamp': 0
}


def run_command(cmd, cwd=None, timeout=5):
    """Run a shell command and return stdout, or None on error."""
    try:
        result = subprocess.run(
            cmd,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=cwd
        )
        return result.stdout.strip() if result.returncode == 0 else None
    except Exception:
        return None


def get_zellij_sessions():
    """Get list of active Zellij session names."""
    output = run_command('zellij list-sessions -n 2>/dev/null')
    if not output:
        return []

    sessions = []
    for line in output.split('\n'):
        line = line.strip()
        # Skip empty lines, "No active" messages, and EXITED sessions
        if line and not line.startswith('No active') and '(EXITED' not in line:
            # Session names are the first word on each line
            session_name = line.split()[0] if line.split() else line
            sessions.append(session_name)
    return sessions


def get_session_cwd_map():
    """Load session-to-cwd mapping from file."""
    if SESSION_CWD_MAP_FILE.exists():
        try:
            with open(SESSION_CWD_MAP_FILE) as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_session_cwd_map(mapping):
    """Save session-to-cwd mapping to file."""
    try:
        with open(SESSION_CWD_MAP_FILE, 'w') as f:
            json.dump(mapping, f, indent=2)
    except Exception:
        pass


def get_claude_status(session_name):
    """Read Claude Code status for a session."""
    status_file = STATUS_DIR / f'{session_name}.json'
    if status_file.exists():
        try:
            with open(status_file) as f:
                data = json.load(f)
                # Check if status is stale (>5 minutes old)
                if 'timestamp' in data:
                    try:
                        ts = datetime.fromisoformat(data['timestamp'].replace('Z', '+00:00'))
                        age = (datetime.now(ts.tzinfo) - ts).total_seconds()
                        if age > 300:  # 5 minutes
                            data['status'] = 'stale'
                    except Exception:
                        pass
                return data
        except Exception:
            pass
    return {
        'status': 'unknown',
        'activity': None,
        'lastUpdate': None
    }


def get_git_status(cwd):
    """Get git branch status for a directory."""
    if not cwd or not os.path.isdir(cwd):
        return None

    git_dir = os.path.join(cwd, '.git')
    if not os.path.exists(git_dir):
        return None

    # Get current branch
    branch = run_command('git branch --show-current', cwd=cwd)
    if not branch:
        return None

    # Check if merged to dev
    merged_branches = run_command('git branch --merged dev 2>/dev/null', cwd=cwd)
    merged_to_dev = branch in (merged_branches or '').split() if merged_branches else False

    # Check if remote branch exists
    remote_check = run_command(f'git ls-remote --heads origin {branch} 2>/dev/null', cwd=cwd)
    remote_exists = bool(remote_check)

    # Get last commit info
    last_commit = run_command('git log -1 --format="%s" 2>/dev/null', cwd=cwd)

    return {
        'branch': branch,
        'mergedToDev': merged_to_dev,
        'remoteBranchExists': remote_exists,
        'lastCommit': last_commit
    }


def get_all_sessions():
    """Get all session data with caching."""
    now = time.time()
    if _cache['data'] and (now - _cache['timestamp']) < CACHE_TTL_SECONDS:
        return _cache['data']

    sessions = []
    session_names = get_zellij_sessions()
    cwd_map = get_session_cwd_map()

    for name in session_names:
        cwd = cwd_map.get(name)
        claude_status = get_claude_status(name)
        git_status = get_git_status(cwd) if cwd else None

        sessions.append({
            'name': name,
            'workingDirectory': cwd,
            'claude': claude_status,
            'git': git_status
        })

    result = {
        'sessions': sessions,
        'timestamp': datetime.utcnow().isoformat() + 'Z'
    }

    _cache['data'] = result
    _cache['timestamp'] = now

    return result


class SessionStatusHandler(http.server.BaseHTTPRequestHandler):
    """HTTP request handler for session status API."""

    def _set_headers(self, status=200, content_type='application/json'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_OPTIONS(self):
        self._set_headers(204)

    def do_GET(self):
        path = urlparse(self.path).path

        if path == '/api/sessions':
            self._set_headers(200)
            data = get_all_sessions()
            self.wfile.write(json.dumps(data, indent=2).encode())

        elif path == '/api/health':
            self._set_headers(200)
            self.wfile.write(json.dumps({'status': 'ok'}).encode())

        elif path == '/':
            self._set_headers(200, 'text/html')
            html = '''<!DOCTYPE html>
<html>
<head><title>Session Status Server</title></head>
<body>
<h1>Zellij Session Status Server</h1>
<p>Endpoints:</p>
<ul>
<li><a href="/api/sessions">/api/sessions</a> - List all sessions</li>
<li><a href="/api/health">/api/health</a> - Health check</li>
</ul>
</body>
</html>'''
            self.wfile.write(html.encode())

        else:
            self._set_headers(404)
            self.wfile.write(json.dumps({'error': 'Not found'}).encode())

    def log_message(self, format, *args):
        # Suppress default logging, or customize as needed
        pass


def main():
    # Ensure status directory exists
    STATUS_DIR.mkdir(exist_ok=True)

    with socketserver.TCPServer(('', PORT), SessionStatusHandler) as httpd:
        print(f'Session Status Server running on port {PORT}')
        print(f'Status directory: {STATUS_DIR}')
        print(f'Session CWD map: {SESSION_CWD_MAP_FILE}')
        print(f'Endpoints:')
        print(f'  http://localhost:{PORT}/api/sessions')
        print(f'  http://localhost:{PORT}/api/health')
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print('\nShutting down...')


if __name__ == '__main__':
    main()
