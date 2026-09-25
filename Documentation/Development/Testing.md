# Testing and validation

[Documentation index](../README.md) · [Project overview](../../README.md)

Recorded test counts describe the commit they were recorded on. Use the CI run of
the exact commit you intend to ship.

## Local setup

| Tool | Version | Used for |
| --- | --- | --- |
| JDK | 17 and 21 | 17 builds everything; Robolectric runs the Android unit tests on 21 (Gradle toolchains find both) |
| Android SDK | platform 37 | `socketio-android`, `sample`, `consumer`; set `sdk.dir` in `local.properties` or `ANDROID_HOME` |
| Node.js | 24 | End-to-end fixtures, parser differential, upstream suites |
| Python | 3.9+ | Validators |
| OpenSSL | CLI on `PATH` | The TLS fixtures generate throwaway certificates per run |

```sh
(cd fixtures && npm ci --ignore-scripts --no-audit --no-fund)
./gradlew check
```

`check` compiles every module with warnings as errors and runs the unit tests,
the Robolectric suite, the end-to-end suite, ktlint (`lintKotlin`), detekt,
Android lint and the public API check (`apiCheck`). The end-to-end harness
installs the fixture dependencies on first use and starts one Node process per
server; the processes end with the JVM even when a run is killed.

On Linux/aarch64 Robolectric has no native runtime for SDK 35+, so the Android
unit tests run on SDK 34 there and on SDK 34 and 36 elsewhere.

## Suites

| Suite | Where | What it proves |
| --- | --- | --- |
| Parser unit and property tests | `engineio-parser`, `socketio-parser` | Upstream parser tests, JavaScript JSON semantics, 20,000 seeded malformed inputs, round-trip properties |
| Client unit tests | `engineio-client`, `socketio-client` | Upstream engine and client tests against in-memory servers on virtual time; every timeout and backoff value is asserted exactly |
| Concurrency | `socketio-client` `ConcurrencyTest` | Lincheck model checking and stress tests of the executor and listener registry; multi-threaded emits |
| OkHttp | `socketio-okhttp` | The HTTP/WebSocket stack against MockWebServer; TLS policies against real TLS handshakes |
| Android (Robolectric) | `socketio-android` `src/test` | Network callbacks, lifecycle, logging, clock, org.json, trim-memory, KeyChain |
| Android (device) | `socketio-android` `src/androidTest` | Real OkHttp, ConnectivityManager and main-thread callbacks on an emulator against the host fixture |
| End-to-end | `e2e-tests` | Upstream client and engine suites against Node ports of their original servers; HTTPS/WSS, mTLS, pins; resilience scenarios |
| Parser differential | `scripts/test-parser-parity.sh` | This codec vs. the pinned JavaScript parser in both directions |
| Upstream suites | `scripts/test-upstream-clients.sh` | The inventoried upstream tests exist and pass on the reference implementation |
| Consumer | `scripts/test-consumer.sh` | The README quick start compiles against the published artifacts with the Kotlin that AGP bundles |

Tests never sleep to prove something: they wait for an event with a deadline or
drive virtual time. A skipped test fails the parity gate. Name parameterized tests
`@ParameterizedTest(name = "{displayName} [{index}] {0}")`: the gate matches JUnit
reports to contracts by method name, and JUnit's default name is only the index.

## Parity evidence

```sh
./gradlew test
python3 scripts/check-parity-contracts.py --strict \
  --junit '*/build/test-results/**/*.xml' --summary /tmp/parity-summary.json
```

The first command must run every suite in the same checkout. The validator
then requires every test named by a contract to appear as passed in the JUnit
reports, rejects any failed or skipped test, and fails while a supported upstream
row has no certifying contract. Without `--junit` it checks only that the
mappings are consistent with the test sources. [PARITY.md](../../PARITY.md)
quotes only numbers from this summary.

With a checkout of `socketio/socket.io` at the pinned commit:

```sh
bash scripts/test-parser-parity.sh ../socket.io /tmp/decoder-differential.json
bash scripts/test-upstream-clients.sh ../socket.io /tmp/upstream-results
```

The engine.io-client upstream suite listens on port 3000 on every interface; free
that port first.

## Device tests

```sh
bash scripts/test-device.sh
```

Needs a running emulator or connected device. The script starts
`fixtures/server.js` and passes its port to the instrumentation. The emulator
reaches the host as `10.0.2.2`; a physical device needs `adb reverse` and a
changed URL. Emulators need hardware virtualization; on machines without it
(for example ARM64 servers without KVM) only CI runs these tests.

## Other checks

```sh
python3 scripts/test-documentation.py && python3 scripts/check-documentation.py
python3 scripts/test-review-regressions.py
bash scripts/test-consumer.sh
./gradlew koverXmlReport   # per module (own tests) in */build/reports/kover/report.xml
./gradlew :koverHtmlReport # JVM modules across all JVM suites incl. e2e: build/reports/kover/html
./gradlew apiDump          # only for intended public API changes; review the .api diff
```

## CI responsibilities

| Job | Evidence |
| --- | --- |
| `hygiene` | Documentation links and anchors, validator regression tests, static contract consistency, shell syntax, no tracked build output or key material |
| `jvm-tests` (JDK 17 and 21) | Build, unit, property, concurrency and Robolectric tests, ktlint, detekt, Android lint, API check, coverage |
| `e2e` | Fixture protocol proofs and the end-to-end suites |
| `parity-gate` | Strict contracts with passed executions from `jvm-tests` (JDK 17) and `e2e` of the same run; uploads the summary |
| `parser-parity` | Decoder and encoder differential against the pinned JavaScript parser |
| `upstream-clients` | Upstream Node suites and a fresh declaration inventory |
| `consumer` | README quick start against the published artifacts |
| `android-emulator` (API 26 and 36) | Instrumented tests on x86_64 emulators |

Each job covers something the others do not. A green upstream suite says nothing
about Kotlin; a green Kotlin suite alone does not prove parity.
