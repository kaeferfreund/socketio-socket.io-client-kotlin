#!/usr/bin/env bash
# Runs the instrumented tests of socketio-android on a connected emulator or device
# against fixtures/server.js on this machine (reached from the emulator as 10.0.2.2).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$ROOT/fixtures" && [ -d node_modules ] || npm ci --ignore-scripts --no-audit --no-fund)
LOG="$(mktemp)"
# The fixture binds 127.0.0.1 only; the emulator's 10.0.2.2 is the host loopback.
node "$ROOT/fixtures/server.js" > "$LOG" 2>&1 < /dev/null &
SERVER=$!
trap 'kill "$SERVER" 2>/dev/null || true; rm -f "$LOG"' EXIT
for _ in $(seq 1 100); do
  PORT="$(sed -n 's/^READY port=\([0-9]*\).*/\1/p' "$LOG")"
  [ -n "$PORT" ] && break
  kill -0 "$SERVER" 2>/dev/null || { cat "$LOG"; exit 1; }
  sleep 0.1
done
[ -n "${PORT:-}" ] || { echo "fixture did not start"; cat "$LOG"; exit 1; }
"$ROOT/gradlew" -p "$ROOT" :socketio-android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.fixturePort="$PORT"
