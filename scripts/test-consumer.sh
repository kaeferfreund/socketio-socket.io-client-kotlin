#!/usr/bin/env bash
# Publishes every library to mavenLocal and compiles the README quick start in an
# independent Gradle build (consumer/) that sees only the published artifacts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(sed -n 's/^VERSION_NAME=//p' "$ROOT/gradle.properties")"
"$ROOT/gradlew" -p "$ROOT" --quiet publishToMavenLocal
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
"$ROOT/gradlew" -p "$ROOT/consumer" --quiet -PsocketioVersion="$VERSION" assembleRelease
echo "PASS: the README quick start compiles against the published $VERSION artifacts."
