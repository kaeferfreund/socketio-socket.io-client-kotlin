# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/).

## Unreleased — 1.0.0

First release: a Socket.IO 4 client for Kotlin and Android, ported from the
official JavaScript client at `socketio/socket.io@aaf2af36`.

### Client

- `SocketManager` and `Socket` with the JavaScript semantics: multiplexed
  namespaces, reconnection with jittered backoff, `connect_error` and
  `disconnect` reasons, send and receive buffering, volatile emits, catch-all
  listeners for incoming and outgoing events.
- Acknowledgements as callbacks or suspending `emitWithAck`, with per-emit and
  default timeouts, ordered retries (`retries`, `ackTimeout`) and cancellation
  that withdraws the packet.
- Connection state recovery (`recovered`, replayed events) and
  `clearRecoveryState()`.
- Authentication as an object or a suspending `AuthProvider` that runs before
  every connection attempt.
- `SocketIO.io()` with the JavaScript manager cache (`forceNew`, `multiplex`).
- Engine.IO 4 over HTTP long-polling and WebSocket: upgrade, `tryAllTransports`,
  `rememberUpgrade`, heartbeat, cookies (`withCredentials`), `extraHeaders`,
  per-transport options, custom transports.
- `SocketIOValue`, an immutable JSON model with JavaScript number formatting, key
  order and coercion; conversion from Kotlin and Java values including
  `org.json` types.
- Optional limits for buffers, packet sizes, nesting depth, attachments,
  polling bodies and handshake values.
- Flows for connection state, socket events and manager events; plugins and a
  tracer hook.

### Modules

- `socketio-okhttp`: OkHttp 5 transport stack, discovered automatically; TLS
  policies with private trust anchors, public-key pins and client certificates.
- `socketio-android`: network monitoring and binding, background policy, Doze-safe
  elapsed-realtime clock, logcat logging, Perfetto sections, `TrafficStats`
  tagging, trim-memory handling, KeyChain client certificates, org.json adapters,
  saved configuration, main-thread callbacks.
- `socketio-serialization`: kotlinx.serialization helpers for emits, listeners and
  acknowledgements.
- `socketio-testing`: in-memory Engine.IO and Socket.IO servers for tests on
  virtual time.

### Compatibility

- Android `minSdk` 26, JVM 17; apps on Kotlin 2.2 or newer
  (`socketio-serialization`: 2.3.20).
- Socket.IO 4.x servers (Engine.IO protocol 4). Socket.IO 2.x servers are
  detected and rejected with a clear error.
- WebTransport is not supported.

### Findings from the socket.io-client-java issues and forks

- WebSocket writes are paced below OkHttp's 16 MiB outgoing queue, which OkHttp
  answers with a close and browsers and Node do not have (socket.io-client-java
  #773, #726: bursts of large emits ended in `transport close`). A single message
  above 16 MiB fails with a transport error that names the limit.
- Regression tests for primitive arrays inside `org.json` payloads (#743),
  JSON-looking acknowledgement strings (#567) and many connect/disconnect cycles
  (#289); end-to-end tests wait for events instead of fixed delays.
- `SECURITY.md` and Dependabot (#785); the JVM process-exit behaviour of OkHttp's
  dispatcher threads is documented (#324).
- The reconnection delay stays at `reconnectionDelayMax` after about 1,024 failed
  attempts in a row, where JavaScript's backoff degenerates to 0 ms (a deliberate
  deviation, documented in PARITY.md).
- See [the triage report](Documentation/UpstreamIssueTriage-2026-09-25.md).

### Validation

- Every supported runtime test declaration of the pinned upstream
  `socket.io-client`, `engine.io-client`, `socket.io-parser` and
  `engine.io-parser` suites has an assertion contract whose Kotlin tests must pass
  in the same CI run; see [PARITY.md](PARITY.md) for the numbers and the reviewed
  exclusions.
- A parser differential against the pinned JavaScript parser, end-to-end suites
  against Node servers (HTTP, HTTPS, WS, WSS, mutual TLS), Lincheck concurrency
  tests, Robolectric and emulator tests.
