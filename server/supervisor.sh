#!/usr/bin/env bash
# Supervisor for server.js: keeps it running and restarts it if it dies.
# Started fully detached by start-server.sh. A separate script file so its
# process command line does not contain "node server.js".
set -u
DIR="$1"; LOG="$2"; PIDFILE="$3"
cd "$DIR"
echo $$ > "$PIDFILE"
trap 'exit 0' TERM INT
# The admin code comes from the environment; .env.local is the local copy and is
# gitignored. Without it the server still runs, but the console stays closed
# (503 admin not configured) rather than falling back to a default anyone knows.
if [ -f "$DIR/.env.local" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$DIR/.env.local"
  set +a
  echo "[supervisor] loaded .env.local" >> "$LOG"
else
  echo "[supervisor] WARNING: no .env.local - admin console will be closed" >> "$LOG"
fi

while :; do
  echo "[supervisor] spawning server $(date -Is)" >> "$LOG"
  node server.js >> "$LOG" 2>&1 &
  NODE_PID=$!
  echo "$NODE_PID" > "$DIR/server.node.pid"
  wait "$NODE_PID"
  echo "[supervisor] server exited with code $? - restarting in 2s" >> "$LOG"
  sleep 2
done
