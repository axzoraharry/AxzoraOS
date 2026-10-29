#!/usr/bin/env bash
# Keep the public localtunnel to the AxzoraOS server alive (auto-restart).
# The free localtunnel client exits if its stdin reaches EOF, so we feed it a
# FIFO whose write end stays open; the client then runs in the foreground of a
# loop that restarts it if the tunnel ever drops.
#
#   bash start-tunnel.sh           # start / (re)start the tunnel supervisor
#   bash start-tunnel.sh stop      # stop it
#   bash start-tunnel.sh status    # is it up?
#
# The public URL is fixed via --subdomain: https://axzora-server.loca.lt
set -u
LT=/data/data/com.dsharnessmobile.shell/files/home/lt/node_modules/localtunnel/bin/lt.js
LOG=/storage/emulated/0/XxX/localtunnel.log
PIDFILE=/storage/emulated/0/XxX/localtunnel.pid
FIFO=/data/data/com.dsharnessmobile.shell/files/home/lt/stdin.fifo

stop() {
  [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null || true
  pkill -f 'lt\.js' 2>/dev/null || true
  rm -f "$PIDFILE"
  echo "tunnel stopped"
}

case "${1:-start}" in
  stop) stop; exit 0 ;;
  status)
    if pgrep -f 'lt\.js' >/dev/null 2>&1; then
      echo "running (url https://axzora-server.loca.lt)"
    else
      echo "not running"
    fi
    exit 0 ;;
esac

stop; sleep 1
[ -p "$FIFO" ] || mkfifo "$FIFO" 2>/dev/null

setsid nohup bash -c '
  LT="$1"; LOG="$2"; PIDFILE="$3"; FIFO="$4"
  echo $$ > "$PIDFILE"
  # Hold the FIFO write-end open so lt.js never sees EOF on stdin.
  ( exec 3>"$FIFO"; while :; do sleep 100; done ) &
  while :; do
    echo "[tunnel] starting $(date -Is)" >> "$LOG"
    node "$LT" --port 8787 --subdomain axzora-server < "$FIFO" >> "$LOG" 2>&1
    echo "[tunnel] exited with $?; restarting in 3s" >> "$LOG"
    sleep 3
  done
' -- "$LT" "$LOG" "$PIDFILE" "$FIFO" >/dev/null 2>&1 &
disown
sleep 2
echo "tunnel started (auto-restart). log: $LOG"
