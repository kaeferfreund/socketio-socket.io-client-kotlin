# Repository layout

[Documentation index](../README.md) · [Contributing](../../CONTRIBUTING.md)

| Path | Contents |
| --- | --- |
| `engineio-parser/`, `socketio-parser/`, `engineio-client/`, `socketio-client/` | The platform-independent client (JVM 17 bytecode) |
| `socketio-okhttp/`, `socketio-android/`, `socketio-serialization/`, `socketio-testing/` | Integrations and test support |
| `*/api/*.api` | Public API dumps checked by `./gradlew apiCheck`; update with `apiDump` only for intended API changes |
| `e2e-tests/` | JUnit suites against real Node servers |
| `parser-parity/` | Command-line tool driven by `scripts/test-parser-parity.sh` |
| `sample/` | Compose chat sample for the emulator |
| `consumer/` | Independent Gradle project compiling the README quick start against `mavenLocal()` |
| `fixtures/` | Node servers and their lockfile ([index](../../fixtures/README.md)) |
| `scripts/` | Validators and helpers ([index](../../scripts/README.md)) |
| `build-logic/` | Convention plugins: compiler flags, lint, detekt, Kover, API checks, publishing |
| `gradle/libs.versions.toml` | Every dependency and plugin version |
| `config/detekt.yml` | Static analysis rules |
| `Documentation/Guides/` | Application guides |
| `Documentation/Development/` | Contributor documentation |
| `Documentation/JavaScriptTestInventory.csv`, `Documentation/JavaScriptParityContracts.json` | Parity inputs of `scripts/check-parity-contracts.py`; paths are stable |
| `Documentation/ReviewEvidence/` | Recorded outputs ([index](../ReviewEvidence/README.md)) |
| `.github/workflows/ci.yml` | All CI jobs |

Test file paths, class and method names are referenced by the parity contracts.
Renaming a test means updating its contract in the same change; the validator
fails otherwise.

## Documentation rules

Keep the README an entry point. Put application recipes under
`Documentation/Guides`, contributor instructions under `Documentation/Development`,
and link new pages from the [documentation index](../README.md). Update the
relevant guide when an API, a default or a toolchain requirement changes. Use
relative links; `python3 scripts/check-documentation.py` validates local links
and heading anchors offline.
