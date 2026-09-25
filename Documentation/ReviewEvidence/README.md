# Review evidence

[Documentation index](../README.md) · [PARITY.md](../../PARITY.md)

Recorded outputs of the validation tools. Each file describes the commit that
added or last changed it; CI produces fresh copies as run artifacts.

| File | Produced by | Contents |
| --- | --- | --- |
| [ParitySummary.json](ParitySummary.json) | `python3 scripts/check-parity-contracts.py --strict --junit '*/build/test-results/**/*.xml' --summary …` after a full `./gradlew test` | Inventory status counts, certified rows, contract and test counts, passed/failed JUnit totals. The only source of the numbers in PARITY.md |
| [CoverageSummary.json](CoverageSummary.json) | The `coverage-summary.json` artifact of a CI run (`scripts/coverage-summary.py`) | Line, branch and method coverage: JVM modules across every JVM suite including end-to-end, and `socketio-android` under Robolectric. The README quotes it |
| [ApiParitySummary.json](ApiParitySummary.json) | `python3 scripts/check-api-parity.py --upstream <socket.io checkout> --summary …` | How the public JavaScript API maps to Kotlin, per surface and status. The README quotes it |
| [DecoderDifferential.json](DecoderDifferential.json) | `bash scripts/test-parser-parity.sh <socket.io checkout> …` | Pinned JavaScript parser vs. this codec: 5,000 valid packets, 29 malformed or non-canonical headers, 1,000 packets in each encode direction, and every difference found |

The live validator inputs are
[JavaScriptTestInventory.csv](../JavaScriptTestInventory.csv) and
[JavaScriptParityContracts.json](../JavaScriptParityContracts.json); CI checks them
on every run, so they cannot drift from the tests.
