# Architecture and source map

[Documentation index](../README.md) · [Project overview](../../README.md)

## Layers

```text
Socket (one namespace)              socketio-client
      │  events, acknowledgements, retries, recovery
      ▼
SocketManager                       socketio-client
      │  namespaces, reconnection with backoff, Socket.IO codec
      ▼
EngineSocket                        engineio-client
      │  Engine.IO 4 handshake, heartbeat, upgrade, write buffer
      ├──────────────────┐
      ▼                  ▼
PollingTransport    WebSocketTransport
      │                  │
      └──── EngineHttpClient / EngineWebSocketFactory ────┐
                                                          ▼
                                             socketio-okhttp (OkHttp 5)
```

The client and engine are line-by-line ports of `socket.io-client` and
`engine.io-client` at the pinned reference commit; method names follow the
JavaScript source so a reviewer can compare both side by side. The parsers are
ports of `socket.io-parser` and `engine.io-parser`.

`socketio-client` and everything below it have no Android or OkHttp dependency:
the HTTP and WebSocket stack is an interface (`EngineClients`), discovered from
`socketio-okhttp` through `ServiceLoader` or passed explicitly. `socketio-android`
adds platform behaviour through the same public extension points an application
could use (`SocketManagerPlugin`, `SocketTracer`, `SocketLogger`, `TimeSource`,
`setNetworkAvailable`, `pause`/`resume`).

## Modules

| Module | Responsibility |
| --- | --- |
| `engineio-parser` | Engine.IO packets, payloads, Base64 and stream framing |
| `socketio-parser` | `SocketIOValue` (the JSON model), JSON text with JavaScript semantics, Socket.IO packet encoder and decoder |
| `engineio-client` | `ProtocolExecutor`, `EngineSocket`, transports, URI and cookie handling, the HTTP/WebSocket abstraction |
| `socketio-client` | `SocketManager`, `Socket`, options, backoff, `SocketIO.io()` lookup, plugins and tracing hooks |
| `socketio-okhttp` | OkHttp implementation of the HTTP/WebSocket abstraction, `TlsPolicy` |
| `socketio-android` | Network monitoring and binding, background policy, elapsed-realtime clock, logcat, Perfetto, trim-memory, KeyChain, org.json |
| `socketio-serialization` | kotlinx.serialization helpers |
| `socketio-testing` | In-memory `FakeEngineServer`/`FakeSocketIOServer` and the Node fixture launcher |
| `e2e-tests`, `parser-parity`, `sample` | Real-server suites, the parser differential tool and the Compose sample (not published) |

## Threading model

Every manager owns one `ProtocolExecutor`: the configured dispatcher limited to
parallelism 1. All state of the engine, its transports, the manager and every
socket is read and written only there; it is the counterpart of the JavaScript
event loop. Public methods (`emit`, `connect`, `on`, …) are safe from any thread:
they run inline when already on the executor, which keeps JavaScript's ordering
for a listener that emits, and are queued in FIFO order otherwise. Timers
(reconnect delay, heartbeat, acknowledgement timeouts) are coroutines on the same
executor, so a test dispatcher drives them on virtual time.

Listener and acknowledgement callbacks run on the executor unless
`callbackDispatcher` is set (Android sets `Dispatchers.Main.immediate`).
Exceptions from application callbacks go to `listenerErrorHandler` and never
reach protocol state. Transport I/O happens on OkHttp's threads; results are
posted back to the executor together with a transport generation, so a retired
transport cannot change the state of a newer one.

Suspending APIs (`emitWithAck`, `awaitConnect`, auth providers) are cancellable:
cancelling `emitWithAck` withdraws the acknowledgement, the buffered packet and
the retry entry on the executor.

## Peer input

Everything received from the network is treated as hostile. The decoders never
throw anything but their parse exception type; a parse error closes the
connection with `parse error`, as in JavaScript. Optional limits bound text
length, nesting depth, attachments, buffered packets, polling bodies and the
handshake's heartbeat values. The JSON model is iterative (no recursion) and
caches hashes, so deep or large payloads cannot overflow the stack.

## Ownership

Keep the `SocketManager` for as long as its sockets are needed and call
`close()` when done; it cancels the executor's timers. A `Socket` belongs to one
manager and one namespace; `manager.socket(nsp)` returns the existing instance.
`SocketIO.io()` caches managers like the JavaScript `io()`; managers created
directly are never cached.

## Tests

Unit tests use the in-memory fakes from `socketio-testing` on a
`StandardTestDispatcher`, so timeouts and backoff are asserted exactly and
without sleeping. `e2e-tests` starts the Node fixtures from `fixtures/`. The
[testing guide](Testing.md) lists every suite and how parity is gated.
