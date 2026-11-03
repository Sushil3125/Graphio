#!/usr/bin/env bash
# Install codemap as an always-on user service.
# Uses systemd --user if systemd is PID 1 (WSL with systemd=true in /etc/wsl.conf, or regular Linux);
# otherwise prints how to use the nohup fallback (scripts/start-codemap.sh).
#
# Usage: scripts/install-service.sh [DEFAULT_PROJECT_ROOT] [PORT]
# Every project gets its own map at /open?root=<dir> (the PyCharm plugin does this); the optional
# DEFAULT_PROJECT_ROOT is only what "/" shows. Without it "/" lists the open projects.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CODEMAP_HOME="$(cd "$HERE/.." && pwd)"
ROOT="${1:-${CODEMAP_ROOT:-}}"
PORT="${2:-${CODEMAP_PORT:-8765}}"
PY="${CODEMAP_PYTHON:-$(command -v python3)}"
[ -n "$ROOT" ] && ROOT="$(cd "$ROOT" && pwd)"

mkdir -p "$HOME/.config/codemap"
ENVF="$HOME/.config/codemap/env"
if [ ! -f "$ENVF" ]; then
  cat > "$ENVF" <<EOT
CODEMAP_HOME=$CODEMAP_HOME
CODEMAP_ROOT=$ROOT
CODEMAP_PORT=$PORT
CODEMAP_HOST=127.0.0.1
CODEMAP_PYTHON=$PY
CODEMAP_ARGS=
EOT
  echo "wrote $ENVF"
else
  echo "keeping existing $ENVF"
fi

if [ "$(ps -p 1 -o comm= 2>/dev/null)" = "systemd" ] && systemctl --user show-environment >/dev/null 2>&1; then
  mkdir -p "$HOME/.config/systemd/user"
  cp "$HERE/codemap.service" "$HOME/.config/systemd/user/codemap.service"
  systemctl --user daemon-reload
  systemctl --user enable --now codemap.service
  # keep user services running without an open login session (needed on WSL / headless)
  loginctl enable-linger "$USER" 2>/dev/null || echo "note: 'loginctl enable-linger $USER' failed; run it with sudo if the service stops when you log out"
  sleep 1
  systemctl --user --no-pager status codemap.service | head -n 12 || true
  echo "codemap: http://127.0.0.1:$PORT/   (logs: journalctl --user -u codemap -f)"
else
  echo "systemd is not PID 1 here; using the nohup fallback."
  "$HERE/start-codemap.sh" start
  echo "To start it automatically, add this line to ~/.bashrc (or ~/.profile):"
  echo "  \"$HERE/start-codemap.sh\" start >/dev/null 2>&1"
fi
