#!/usr/bin/env bash
# Compare the real Kotlin codec against hash-verified, pinned upstream JS source.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM="${1:?Usage: test-parser-parity.sh /path/to/socket.io [result.json]}"
export PARITY_OUTPUT="${2:-$PWD/decoder-differential-results.json}"
PARITY_TEMP="$(mktemp -d)"
export PARITY_TEMP
trap 'rm -rf "$PARITY_TEMP"' EXIT
test "$(git -C "$UPSTREAM" rev-parse HEAD)" = aaf2af36ec8ad05910f357a788e0e358bad32738
node "$ROOT/scripts/parser-parity/prepare.cjs" "$UPSTREAM"
"$ROOT/gradlew" -p "$ROOT" --quiet :parser-parity:installDist
export PARITY_DECODER="$ROOT/parser-parity/build/install/parser-parity/bin/parser-parity"
node "$ROOT/scripts/parser-parity/compare.cjs"
