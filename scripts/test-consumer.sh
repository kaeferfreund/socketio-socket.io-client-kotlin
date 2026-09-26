#!/usr/bin/env bash
# Compiles the README quick start in an independent Gradle build (consumer/) that
# sees only published artifacts. By default it first publishes every library to
# mavenLocal; with --maven-central it resolves VERSION_NAME from Maven Central.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(sed -n 's/^VERSION_NAME=//p' "$ROOT/gradle.properties")"
SOURCE="mavenLocal"
CONSUMER_ARGS=()
if [ "${1:-}" = "--maven-central" ]; then
  SOURCE="Maven Central"
  CONSUMER_ARGS+=(-PsocketioFromMavenCentral --refresh-dependencies)
elif [ $# -gt 0 ]; then
  echo "usage: $0 [--maven-central]" >&2
  exit 2
else
  "$ROOT/gradlew" -p "$ROOT" --quiet publishToMavenLocal
fi
mkdir -p "$ROOT/consumer/src/main/kotlin"
python3 - "$ROOT/README.md" "$ROOT/consumer/src/main/kotlin/QuickStart.kt" <<'PY'
import re, sys
readme = open(sys.argv[1], encoding='utf-8').read()
blocks = re.findall(r'<!-- quick-start:begin -->\n```kotlin\n(.*?)```\n<!-- quick-start:end -->', readme, re.S)
if len(blocks) != 1:
    sys.exit('README.md must contain exactly one marked Kotlin quick start')
open(sys.argv[2], 'w', encoding='utf-8').write('// Generated from README.md by scripts/test-consumer.sh.\n' + blocks[0])
PY
if [ ! -f "$ROOT/consumer/local.properties" ] && [ -f "$ROOT/local.properties" ]; then
  cp "$ROOT/local.properties" "$ROOT/consumer/local.properties"
fi
"$ROOT/gradlew" -p "$ROOT/consumer" --quiet -PsocketioVersion="$VERSION" ${CONSUMER_ARGS[@]+"${CONSUMER_ARGS[@]}"} assembleRelease
echo "PASS: the README quick start compiles against the $VERSION artifacts from $SOURCE."
