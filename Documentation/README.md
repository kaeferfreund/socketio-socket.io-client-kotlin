# Documentation

[Project overview](../README.md) · [Contributing](../CONTRIBUTING.md)

Start with the application guides. Parity evidence and development notes are
separate references, not prerequisites for using the library.

## Application guides

| Topic | Read |
| --- | --- |
| Requirements, Gradle setup and the first connection | [Getting started](Guides/GettingStarted.md) |
| Supported servers, Android/JVM versions and JavaScript option names | [Compatibility](Guides/Compatibility.md) |
| Listeners, payloads, binary data, volatile events, flows and serialization | [Events and payloads](Guides/Events.md) |
| Callback and suspending acknowledgements, timeouts and ordered retries | [Acknowledgements and delivery](Guides/Acknowledgements.md) |
| Authentication, namespaces, reconnection, session recovery and pausing | [Connection lifecycle](Guides/Connections.md) |
| Transports, buffers, cookies, TLS, limits and threading | [Configuration](Guides/Configuration.md) |
| Network changes, background policy, Doze, logging, KeyChain and R8 | [Android integration](Guides/Android.md) |
| Missing events and connection failures | [Troubleshooting](Guides/Troubleshooting.md) |

Examples using `socket` assume an existing socket created by a `SocketManager`.
They are independent recipes, not one file to paste wholesale. The README quick
start is compiled by CI as an independent Gradle consumer.

## Migration and releases

| Topic | Read |
| --- | --- |
| Move from `io.socket:socket.io-client` (Java) 2.x | [Migration](Guides/Migration.md) |
| Version history | [Changelog](../CHANGELOG.md) |

## Library development

| Topic | Read |
| --- | --- |
| Modules, threading model and ownership | [Architecture](Development/Architecture.md) |
| Local prerequisites, commands and CI jobs | [Testing](Development/Testing.md) |
| Release checks, versions and publishing | [Releasing](Development/Releasing.md) |
| Where files belong | [Repository layout](Development/RepositoryLayout.md) |
| Purpose and prerequisites of each helper script | [Script index](../scripts/README.md) |
| The Node servers used by the end-to-end suite | [Fixtures](../fixtures/README.md) |

## API reference

KDoc comments beside the declarations are the reference for this checkout; the
checked-in `api/*.api` files list every public signature and are verified by
`./gradlew apiCheck`.

| API | Declaration |
| --- | --- |
| Connection, namespaces, reconnection | [SocketManager](../socketio-client/src/main/kotlin/io/github/kaeferfreund/socketio/SocketManager.kt) |
| Events, acknowledgements, lifecycle of one namespace | [Socket](../socketio-client/src/main/kotlin/io/github/kaeferfreund/socketio/Socket.kt) |
| Options | [SocketManagerOptions](../socketio-client/src/main/kotlin/io/github/kaeferfreund/socketio/SocketManagerOptions.kt) |
| Result, event and error types | [SocketTypes](../socketio-client/src/main/kotlin/io/github/kaeferfreund/socketio/SocketTypes.kt) |
| Payload model | [SocketIOValue](../socketio-parser/src/main/kotlin/io/github/kaeferfreund/socketio/parser/SocketIOValue.kt) |
| TLS policy | [TlsPolicy](../socketio-okhttp/src/main/kotlin/io/github/kaeferfreund/socketio/okhttp/TlsPolicy.kt) |
| Android options | [AndroidOptions](../socketio-android/src/main/kotlin/io/github/kaeferfreund/socketio/android/AndroidOptions.kt) |
| Test doubles | [FakeSocketIOServer](../socketio-testing/src/main/kotlin/io/github/kaeferfreund/socketio/testing/FakeSocketIOServer.kt) |

## Parity and evidence

[PARITY.md](../PARITY.md) defines the supported scope. The
[contract manifest](JavaScriptParityContracts.json) and
[test inventory](JavaScriptTestInventory.csv) are validator inputs, and so is the
[API mapping](JavaScriptApiMapping.json) of every public JavaScript member; none of them are review
notes: CI rejects a contract whose tests did not pass in the same run. The
[evidence index](ReviewEvidence/README.md) explains the recorded parser
comparison. Numbers in these files describe the commit they were recorded on;
inspect CI for the revision you plan to use.
