#!/usr/bin/env bash
# Start a local Firebase emulator and point both the Android app and the server
# at it. The emulator stores the RTDB at ./firebase-emulator-data.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DATA_DIR="$ROOT/firebase-emulator-data"
PID_FILE="$ROOT/firebase-emulator.pid"
LOG_FILE="$ROOT/firebase-emulator.log"

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Emulator already running (pid $(cat "$PID_FILE"))"
  exit 0
fi

if [[ ! -d "$DATA_DIR" ]]; then
  npx --yes firebase-tools@latest init emulators --project nove-emu --only database 2>/dev/null || true
fi

if [[ ! -f "$DATA_DIR/firebase.json" ]]; then
  cat > "$DATA_DIR/firebase.json" <<'JSON'
{
  "database": { "port": 9000 },
  "emulators": { "database": { "host": "127.0.0.1", "port": 9000 } }
}
JSON
fi

cd "$DATA_DIR"
nohup npx --yes firebase-tools@firebase@13.21.1 emulators:start --only database --project nove-emu \
  > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "Started Firebase RTDB emulator (pid $(cat "$PID_FILE")) on 127.0.0.1:9000"
echo "Logs: $LOG_FILE"
