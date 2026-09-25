# Scripts

[Documentation index](../Documentation/README.md) · [Testing](../Documentation/Development/Testing.md)

| Script | Purpose | Needs |
| --- | --- | --- |
| `check-parity-contracts.py` | Validates inventory and contracts; `--junit <glob>` requires every contract test to have passed (and no test to have failed or been skipped); `--strict` fails while a supported row is uncertified; `--summary` writes the counts; `--upstream-inventory` compares with a fresh upstream scan | Python 3.9+; JUnit XML from `./gradlew test` for `--junit` |
| `test-review-regressions.py` | Unit tests of the parity validator: each way evidence could be faked or lost is rejected | Python 3.9+ |
| `check-api-parity.py` | Checks `Documentation/JavaScriptApiMapping.json`: every JavaScript API member resolves to Kotlin symbols in `api/*.api` or has a reviewed reason; `--upstream <socket.io>` also requires the mapping to match the pinned TypeScript declarations exactly; `--summary` writes the counts | Python 3.9+; the pinned checkout for `--upstream` |
| `test-api-parity.py` | Unit tests of the API mapping checker | Python 3.9+ |
| `coverage-summary.py` | Summarizes the merged JVM and the Android Kover reports as the coverage numbers the README quotes | Python 3.9+; Kover XML reports |
| `check-documentation.py` | Offline check of relative links, heading anchors and the marked README quick start | Python 3.9+ |
| `test-documentation.py` | Unit tests of the documentation checker | Python 3.9+ |
| `test-consumer.sh` | Publishes to `mavenLocal()` and compiles the README quick start in the independent `consumer/` build | JDK 17 and 21, Android SDK |
| `test-parser-parity.sh <socket.io> [out.json]` | Differential test of this codec against the pinned JavaScript parser | Node 24, a `socketio/socket.io` checkout at the pinned commit, `typescript` on `NODE_PATH` (for example `npm install --prefix /tmp/tools typescript@5.8.3`) |
| `test-upstream-clients.sh <socket.io> <out-dir>` | Runs the unmodified upstream Node suites and compares the committed inventory with a fresh scan | Node 24, the pinned checkout, free port 3000 |
| `inventory-upstream-tests.cjs <socket.io> [out-dir]` | AST scan of upstream test declarations (used by the script above) | Node, `typescript` from the upstream checkout (`NODE_PATH`) |
| `parser-parity/prepare.cjs`, `parser-parity/compare.cjs` | Build the pinned parser and compare outputs (used by `test-parser-parity.sh`) | Node |

The upstream checkout must be at `aaf2af36ec8ad05910f357a788e0e358bad32738`; the
scripts refuse any other commit. Do not point them at a moving branch.
