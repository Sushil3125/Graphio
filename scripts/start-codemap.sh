#!/usr/bin/env bash
# Fallback launcher for systems without systemd (e.g. WSL without systemd=true).
# Runs codemap in the background with nohup and a pidfile.
#
# Usage: scripts/start-codemap.sh {start|stop|restart|status|logs}
# Settings come from ~/.config/codemap/env (same file as the systemd unit) or the environment.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENVF="$HOME/.config/codemap/env"
# shellcheck disable=SC1090
[ -f "$ENVF" ] && set -a && . "$ENVF" && set +a

CODEMAP_HOME="${CODEMAP_HOME:-$(cd "$HERE/.." && pwd)}"
CODEMAP_ROOT="${CODEMAP_ROOT:-}"  # optional default project for "/"
CODEMAP_PORT="${CODEMAP_PORT:-8765}"
CODEMAP_HOST="${CODEMAP_HOST:-127.0.0.1}"
CODEMAP_PYTHON="${CODEMAP_PYTHON:-$(command -v python3)}"
CODEMAP_ARGS="${CODEMAP_ARGS:-}"
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/codemap"
PIDFILE="$STATE/codemap.pid"
LOG="$STATE/codemap.log"
mkdir -p "$STATE"

running() {
  [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

start() {
  if running; then
    echo "codemap already running (pid $(cat "$PIDFILE")) at http://$CODEMAP_HOST:$CODEMAP_PORT/"
    return 0
  fi
  cd "$CODEMAP_HOME" || exit 1
  # shellcheck disable=SC2086
  nohup "$CODEMAP_PYTHON" -u -m codemap $CODEMAP_ROOT --host "$CODEMAP_HOST" --port "$CODEMAP_PORT" $CODEMAP_ARGS \
    >>"$LOG" 2>&1 </dev/null &
  echo $! >"$PIDFILE"
  sleep 1.5
  if running; then
    echo "codemap started (pid $(cat "$PIDFILE")) at http://$CODEMAP_HOST:$CODEMAP_PORT/  log: $LOG"
  else
    echo "codemap failed to start; last log lines:" >&2
    tail -n 20 "$LOG" >&2
    rm -f "$PIDFILE"
    return 1
  fi
}

stop() {
  if running; then
    pid="$(cat "$PIDFILE")"
    kill "$pid"
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
    echo "codemap stopped"
  else
    echo "codemap not running"
  fi
  rm -f "$PIDFILE"
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) if running; then echo "running (pid $(cat "$PIDFILE")) http://$CODEMAP_HOST:$CODEMAP_PORT/"; else echo "stopped"; exit 3; fi ;;
  logs) tail -n 100 -f "$LOG" ;;
  *) echo "usage: $0 {start|stop|restart|status|logs}" >&2; exit 2 ;;
esac
