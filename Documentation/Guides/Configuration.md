# Configuration

[Documentation index](../README.md) · [Project overview](../../README.md)

All options live in two immutable objects built with DSL functions:

- `SocketManagerOptions { … }` — one connection: the JavaScript `ManagerOptions`
  (including the Engine.IO options) plus Kotlin runtime settings.
- `SocketOptions { … }` — one namespace socket: `auth`, `authProvider`, `retries`,
  `ackTimeout`, `outgoingInterceptor`. See [Connections](Connections.md) and
  [Acknowledgements](Acknowledgements.md).

Every default is the JavaScript client's default. Durations are
`kotlin.time.Duration`, not milliseconds. On Android, call `android(context)`
first; it sets the clock, callback thread, logger, plugins and HTTP stack (see
[Android integration](Android.md)).

```kotlin
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.Transport
import kotlin.time.Duration.Companion.seconds

val options = SocketManagerOptions {
    reconnectionDelayMax = 30.seconds
    timeout = 10.seconds
    transports = listOf(Transport.WEBSOCKET, Transport.POLLING)
    tryAllTransports = true
}
val manager = SocketManager("https://example.com", options)
```

`options.newBuilder()` returns a pre-filled builder for a modified copy.

## Reconnection

| Option | Default | Meaning |
| --- | --- | --- |
| `reconnection` | `true` | Reconnect automatically after an unexpected close |
| `reconnectionAttempts` | `Int.MAX_VALUE` (unlimited) | Attempts before `ManagerEvent.ReconnectFailed` |
| `reconnectionDelay` | `1.seconds` | First backoff delay |
| `reconnectionDelayMax` | `5.seconds` | Backoff ceiling |
| `randomizationFactor` | `0.5` | Jitter, between 0 and 1 |
| `random` | `Random.Default` | Source of the jitter (tests pass a seeded one) |

`reconnection`, `reconnectionAttempts`, `reconnectionDelay`,
`reconnectionDelayMax`, `randomizationFactor` and `timeout` are also mutable
properties of a running `SocketManager`, like the JavaScript setters.

## Connection

| Option | Default | Meaning |
| --- | --- | --- |
| `autoConnect` | `true` | Open the connection when the manager/socket is created |
| `timeout` | `20.seconds` | Limit for each connection attempt; `null` disables it. Expiry fails the attempt with `connect_error` `"timeout"` |
| `forceNew` | `false` | `SocketIO.io` always creates a new manager |
| `multiplex` | `true` | `SocketIO.io` shares one manager per scheme, host, port and path |

## Transports and HTTP

| Option | Default | Meaning |
| --- | --- | --- |
| `transports` | `[polling, websocket]` | Transport names in the order they are tried; use `Transport.POLLING` / `Transport.WEBSOCKET` |
| `upgrade` | `true` | Probe WebSocket after connecting over polling and switch to it |
| `tryAllTransports` | `false` | If the first transport fails to *open*, try the next one in `transports` |
| `rememberUpgrade` | `false` | Start with WebSocket when a previous connection in this process upgraded successfully |
| `path` | `"/socket.io"` | Request path on the server; must match the server's `path` |
| `addTrailingSlash` | `true` | Append `/` to `path` (`/socket.io/`) |
| `query` | empty | Query parameters on every request |
| `extraHeaders` | empty | Headers on every polling request and on the WebSocket handshake |
| `withCredentials` | `false` | Keep `Set-Cookie` values and resend them within one connection |
| `forceBase64` | `false` | Send binary attachments as Base64 text |
| `timestampRequests` | `null` | Cache-busting parameter: `null` = on for polling, off for WebSocket |
| `timestampParam` | `"t"` | Name of the cache-busting parameter |
| `protocols` | empty | `Sec-WebSocket-Protocol` values to offer |
| `perMessageDeflateThreshold` | `1024` | WebSocket messages below this many bytes are not compressed; `null` disables compression |
| `requestTimeout` | `null` | Whole-request timeout of each polling request |
| `maxPollingResponseBytes` | `Long.MAX_VALUE` | Largest accepted polling response body |
| `handshakeLimits` | unlimited | Upper bounds for `pingInterval` / `pingTimeout` in the server handshake |
| `transportOptions` | empty | Overrides per transport name (below) |
| `transportFactories` | empty | Custom transport implementations by name (below) |
| `clients` | `null` | HTTP/WebSocket stack; discovered from `socketio-okhttp` when `null` |

Notes:

- The path of the URL passed to `SocketManager` is ignored; only scheme, host,
  port and query are used. With `SocketIO.io(url)` the URL path names the
  *namespace*. The request path is always the `path` option.
- `tryAllTransports = true` with `transports = listOf(Transport.WEBSOCKET, Transport.POLLING)`
  gives "WebSocket first, polling if WebSocket is blocked".
- `rememberUpgrade` state is shared by every manager in the process, like the
  static flag in JavaScript. A failed connection clears it.
- `withCredentials` uses a cookie jar per Engine.IO connection; it starts empty on
  every reconnect. For cookies that must survive reconnects (for example sticky
  sessions), set an OkHttp `CookieJar` with `okHttp { configure { cookieJar(…) } }`.
  The application's `CookieManager` is never used implicitly.
- `extraHeaders` holds one value per name. Join repeated values with `", "`.
- **Compression:** OkHttp negotiates `permessage-deflate` and compresses messages
  by size only (`perMessageDeflateThreshold`). The per-message flag of
  `socket.compress(false)` is carried to the transport, but OkHttp offers no way to
  switch compression off for a single frame.
- **Large WebSocket messages:** OkHttp queues at most 16 MiB of outgoing WebSocket
  data. The transport hands it only what fits and waits for the rest, so bursts of
  large emits do not close the connection; a single message above 16 MiB cannot be
  sent over WebSocket (see [Troubleshooting](Troubleshooting.md#limits)).
- `requestTimeout` applies to polling only. OkHttp's read timeout is always
  disabled for the transports because a long poll legitimately waits a full
  heartbeat interval; the heartbeat detects dead connections.
- A polling body larger than `maxPollingResponseBytes`, or a handshake outside
  `handshakeLimits`, fails the connection with a transport error
  (`"xhr poll error"` or `"invalid handshake"`).

### Per-transport options

`TransportOverrides` accepts `query`, `extraHeaders`, `requestTimeout`,
`timestampRequests`, `forceBase64` and `path`. Values are merged over the global
ones for that transport only:

```kotlin
import io.github.kaeferfreund.socketio.engineio.TransportOverrides

val options = SocketManagerOptions {
    transportOptions = mapOf(
        Transport.POLLING to TransportOverrides(requestTimeout = 30.seconds),
        Transport.WEBSOCKET to TransportOverrides(extraHeaders = mapOf("X-Transport" to "ws")),
    )
}
```

### Custom transports

`transportFactories` maps a transport name to an `EngineTransport.Factory`.
Built-in names without an entry use the built-in transport. A factory can wrap a
built-in transport, for example to count connection attempts:

```kotlin
import io.github.kaeferfreund.socketio.engineio.EngineTransport
import io.github.kaeferfreund.socketio.engineio.PollingTransport

val options = SocketManagerOptions {
    transportFactories = mapOf(
        Transport.POLLING to EngineTransport.Factory { transportOptions ->
            println("polling attempt to ${transportOptions.hostname}")
            PollingTransport(transportOptions)
        },
    )
}
```

A new transport subclasses `EngineTransport` and implements `name`, `doOpen`,
`doClose`, `write` and `uri`. All methods run on the protocol executor; hop to
`TransportOptions.executor` before calling the protected `on…` methods from I/O
callbacks. The server must implement the same transport. `uri()` is annotated
`@InternalSocketIOApi`, so an implementation currently needs
`@OptIn(InternalSocketIOApi::class)`.

## OkHttp client

Without configuration the default OkHttp stack is found through `ServiceLoader`.
To customise it on the JVM, use `okHttp { }` from `socketio-okhttp`:

```kotlin
import io.github.kaeferfreund.socketio.okhttp.okHttp
import java.net.Proxy
import okhttp3.OkHttpClient

val sharedClient = OkHttpClient()

val options = SocketManagerOptions {
    okHttp {
        client = sharedClient            // connection pool and dispatcher are reused
        configure { proxy(Proxy.NO_PROXY) }
    }
}
```

The library derives its own client from yours: read timeout disabled,
HTTPS→HTTP redirects not followed (`followSslRedirects = false`), and at least 64
concurrent requests per host. Interceptors, proxy, DNS, cookie jar,
`connectionSpecs` and call timeouts are kept.

On Android, set `okHttpClient` and `tlsPolicy` inside `android(context) { }`
instead. Calling `okHttp { }` after `android(context)` replaces the Android
client, which removes network binding and traffic tagging.

## TLS

`TlsPolicy` covers what Node's `ca`, `cert`, `key`, `pfx` and
`rejectUnauthorized` do in JavaScript. The default needs no configuration.

| Policy | Effect |
| --- | --- |
| `TlsPolicy.systemDefault()` | Platform trust store. On Android, the app's Network Security Config (pins, user CAs, cleartext rules) applies unchanged |
| `TlsPolicy.customTrust(anchors)` | Trust only these certificates (private CA or self-signed leaf) for this client |
| `TlsPolicy.pinned(hostPattern, "sha256/…")` | System trust plus public-key pins |
| `.withPins(hostPattern, …)` / `.withPinnedCertificates(hostPattern, certs)` | Add pins to any policy |
| `.withClientCertificate(pkcs12, password)` / `.withClientCertificate(keyManager)` | Present a client certificate (mutual TLS) |
| `.withKeyChainAlias(context, alias)` (Android) | Client certificate from the Android KeyChain |

Host patterns follow OkHttp's `CertificatePinner`: `api.example.com`,
`*.example.com` (one label) or `**.example.com` (any depth).

```kotlin
import io.github.kaeferfreund.socketio.okhttp.TlsPolicy

/** [caPem], [clientP12] and [password] come from your application's resources or secure storage. */
fun mutualTlsOptions(caPem: ByteArray, clientP12: ByteArray, password: CharArray): SocketManagerOptions {
    val policy = TlsPolicy
        .customTrust(TlsPolicy.certificates(caPem))
        .withPins("api.example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .withClientCertificate(clientP12, password)
    return SocketManagerOptions {
        okHttp { tlsPolicy = policy }
    }
}
```

`TlsPolicy.certificates` parses PEM or DER, one or several certificates.
Hostname verification, validity and chain checks always stay enabled.

There is deliberately no "trust all certificates" mode: it is the most common way
production apps end up accepting any certificate. For development servers, trust
their CA with `customTrust`, or on Android with a debug-only Network Security
Config (see [Android integration](Android.md#network-security-config)).

## Threading

| Option | Default | Meaning |
| --- | --- | --- |
| `dispatcher` | `Dispatchers.Default` | Runs the protocol, limited to parallelism 1 |
| `callbackDispatcher` | `null` (Android: `Dispatchers.Main.immediate`) | Thread for listener and acknowledgement callbacks |
| `listenerErrorHandler` | `null` (log it) | Receives exceptions thrown by listeners |

Each manager owns a `ProtocolExecutor`: one serial context for the engine, its
transports, the manager and every socket. Public methods may be called from any
thread; they hand work to the executor and return immediately.

With `callbackDispatcher = null`, listeners run directly on the executor, which
keeps the exact JavaScript ordering. A listener that blocks then stalls the
connection, heartbeats included. With a dispatcher, callbacks are posted there.
In both cases: **never block in a listener** — no network or disk I/O, no
`runBlocking`, no waiting on an acknowledgement. Launch a coroutine instead.

An exception thrown by a listener never corrupts protocol state; it is passed to
`listenerErrorHandler`, or logged when no handler is set.

`Flow`s (`socket.events`, `socket.state`, `manager.events`) are delivered in the
collector's context, independent of `callbackDispatcher`. `logger`,
`packetObserver`, `tracer` and `OutgoingInterceptor` run on the executor; keep them
fast.

## Time

`timeSource` (default `TimeSource.Monotonic`) measures heartbeat, backoff and
acknowledgement deadlines. `android(context)` sets `ElapsedRealtimeTimeSource`,
which keeps counting in deep sleep, so a heartbeat that expired during Doze is
detected after wake-up. Tests can use a virtual-time dispatcher and matching time
source.

## Diagnostics

| Option | Default | Meaning |
| --- | --- | --- |
| `logger` | `SocketLogger.NONE` (Android: `AndroidLogger("SocketIO")`) | Log sink; messages are built only when `isLoggable` is true |
| `packetObserver` | `null` | Sees every Engine.IO packet in both directions |
| `tracer` | `null` (Android: `AndroidTracer` with `tracing = true`) | Async sections `socket.io connect`, `socket.io upgrade`, `socket.io ack <event>` |
| `plugins` | empty | `SocketManagerPlugin`s attached when a manager is created and cancelled when it is closed |

```kotlin
import io.github.kaeferfreund.socketio.engineio.EnginePacketObserver
import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.engineio.SocketLogger

val options = SocketManagerOptions {
    logger = SocketLogger.console(LogLevel.DEBUG)
    packetObserver = EnginePacketObserver { outgoing, packet ->
        println("${if (outgoing) "->" else "<-"} ${packet.type} ${packet.text.orEmpty().take(200)}")
    }
}
```

Log output and observed packets can contain authentication data and payloads.
Keep them out of production builds.

## Limits

Everything is unlimited by default, as in JavaScript. Two independent layers
exist for apps that process untrusted or unusually large input.

`SocketParserOptions` (`parserOptions`) bounds one incoming packet:

| Field | Default |
| --- | --- |
| `maxAttachments` | `10` (binary attachments per packet, as in `socket.io-parser`) |
| `maxBinaryPacketBytes` | `Long.MAX_VALUE` |
| `maxTextPacketBytes` | `Long.MAX_VALUE` |
| `maxNestingDepth` | `Int.MAX_VALUE` (parsing is iterative; this is a resource bound) |
| `reviver` | `null` (`JSON.parse` reviver) |

A packet over a parser limit closes the connection with `"parse error"`; the
manager reconnects.

`SocketBufferLimits` (`bufferLimits`) bounds what is kept across packets:
`maxSendBufferPackets`/`Bytes`, `maxRetryQueuePackets`/`Bytes`,
`maxReceiveBufferPackets`/`Bytes` and `binaryReconstructionTimeout`. An emit over
an outgoing limit fails with `SocketBufferLimitException` through its
acknowledgement callback and `ManagerEvent.Error`; nothing already accepted is
evicted. A receive-buffer overflow closes the connection with `"transport error"`;
a binary packet still missing attachments after `binaryReconstructionTimeout`
closes it with `"parse error"`. The manager reconnects in both cases.

```kotlin
import io.github.kaeferfreund.socketio.SocketBufferLimits
import io.github.kaeferfreund.socketio.parser.SocketParserOptions
import kotlin.time.Duration.Companion.seconds

val options = SocketManagerOptions {
    parserOptions = SocketParserOptions(maxTextPacketBytes = 1L shl 20, maxBinaryPacketBytes = 8L shl 20)
    bufferLimits = SocketBufferLimits(maxSendBufferPackets = 500, binaryReconstructionTimeout = 30.seconds)
}
```

Byte counts of the buffers are estimates of retained memory, not wire sizes.
