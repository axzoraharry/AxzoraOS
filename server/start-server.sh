#!/usr/bin/env bash
# Keep the AxzoraOS backend (server/server.js) running on this device - always.
#
#   bash start-server.sh            # start / (re)start the persistent server
#   bash start-server.sh stop       # stop it
#   bash start-server.sh status     # is it running?
#
# No wake lock is available in this sandbox (Termux service absent), so Doze
# may pause the server after the device idles. True boot-start needs Termux:Boot.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
LOG="$DIR/server.log"
SPID="$DIR/server.pid"
NPID="$DIR/server.node.pid"

stop() {
  [ -f "$SPID" ] && kill "$(cat "$SPID")" 2>/dev/null && rm -f "$SPID" || true
  [ -f "$NPID" ] && kill "$(cat "$NPID")" 2>/dev/null && rm -f "$NPID" || true
  pkill -f "node server\.js$" 2>/dev/null || true
}

case "${1:-start}" in
  stop) stop; echo "server stopped"; exit 0 ;;
  status)
    if [ -f "$SPID" ] && kill -0 "$(cat "$SPID")" 2>/dev/null; then
      echo "running (supervisor pid $(cat "$SPID"), node pid $(cat "$NPID" 2>/dev/null))"
      echo "last log:"; tail -3 "$LOG" 2>/dev/null
    else
      echo "not running"
    fi
    exit 0 ;;
esac

stop
sleep 1
termux-wake-lock 2>/dev/null || true
setsid nohup bash "$DIR/supervisor.sh" "$DIR" "$LOG" "$SPID" >/dev/null 2>&1 &
disown
sleep 2
echo "started. log: $LOG"
tail -4 "$LOG" 2>/dev/null
