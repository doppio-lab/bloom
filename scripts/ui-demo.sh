#!/usr/bin/env bash
# Serves the dashboard with its built-in example data, without the server, the database or the admin password.
# Usage: scripts/ui-demo.sh [port]   then open http://localhost:<port>/?demo=1 (Ctrl+C to stop).
# The page is copied first, so edits to index.html show up after a restart, never through the real server.
set -euo pipefail
port="${1:-8099}"
src="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/resources/static"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cp "$src/index.html" "$tmp/"
echo "Dashboard demo: http://localhost:$port/?demo=1"
cd "$tmp" && exec python3 -m http.server "$port" --bind 127.0.0.1
