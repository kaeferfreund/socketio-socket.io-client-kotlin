# Troubleshooting

[Documentation index](../README.md) · [Project overview](../../README.md)

## Diagnosing

Log `connect_error` and `disconnect` first; they explain most problems:

```kotlin
val socket = manager.socket("/") {
    onConnectError { error -> Log.w("Sync", "connect_error: $error", error.cause) }
    onDisconnect { reason, details -> Log.w("Sync", "disconnect: ${reason.wireValue} $details") }
}
```

Then enable library logging. On Android (`android(context)` uses the tag
`SocketIO`):

```sh
adb shell setprop log.tag.SocketIO DEBUG
adb logcat -s SocketIO
```

On the JVM, set `logger = SocketLogger.console(LogLevel.DEBUG)`. To see the wire
traffic, add a `packetObserver` (see [Configuration](Configuration.md#diagnostics)).
Logs and packets may contain tokens and payloads; keep them out of production.

Checklist:

- Is the server a Socket.IO **4** server, not a plain WebSocket server?
- Does the `path` option equal the server's `path` (default `/socket.io`)?
- Does the namespace exist on the server?
- Can the device reach the host (emulator: `10.0.2.2`, not `localhost`)?
- Does a proxy forward both polling requests and WebSocket upgrades?
- Does it work with the default `transports` (polling, then upgrade)?

## `connect_error` reference

| Error | Meaning | Look at |
| --- | --- | --- |
| `TransportException` `"xhr poll error"` / `"xhr post error"` | A polling request failed | `statusCode` and `responseBody` if the server answered; otherwise `cause` (DNS, refused, TLS, cleartext) |
| `TransportException` `"websocket error"` | WebSocket handshake or connection failed | `statusCode` of the handshake response, `cause` |
| `TransportException` `"invalid handshake"` | The response was not an Engine.IO 4 handshake, or exceeded `handshakeLimits` | URL, `path`, server version |
| `EngineIOException` `"timeout"` | No connection within the manager `timeout` (20 s) | Reachability, slow proxies |
| `SocketConnectException`, `isServerRefusal = true` | Middleware called `next(new Error(…))`, or the namespace does not exist (`"Invalid namespace"`) | `message`, `data` |
| `SocketConnectException` "…Socket.IO server in v2.x…" | The server is Socket.IO 2 | Upgrade the server; see [Compatibility](Compatibility.md) |
| `AuthProviderException` | The `AuthProvider` threw, or `auth` is not an object | `cause` |

Transport failures are retried with backoff. A server refusal is not: the socket
stays inactive until you call `connect()` again, typically after refreshing
credentials.

## The client never connects

**Cleartext blocked.** Android blocks `http://` and `ws://` by default. The
`cause` is an `UnknownServiceException` "CLEARTEXT communication … not permitted
by network security policy". Use HTTPS, or allow cleartext for development hosts
in a debug-only Network Security Config ([Android](Android.md#network-security-config)).

**Wrong path.** A `404` `statusCode` usually means the `path` option does not match
the server. The path of the URL given to `SocketManager` is *not* the request path;
`SocketManager("https://example.com/api")` still requests `/socket.io/`. Set
`path = "/api/socket.io"` instead. With `SocketIO.io(url)`, the URL path is the
namespace.

**HTTP 400.** The body names the Engine.IO error, for example
`{"code":0,"message":"Transport unknown"}`, `"Session ID unknown"` (a load balancer
without sticky sessions sends polling requests to different nodes) or
`"Unsupported protocol version"`. A `403` usually comes from `allowRequest` or an
authenticating proxy.

**Socket.IO 2 server.** Connections to a v2 server fail, typically with
"It seems you are trying to reach a Socket.IO server in v2.x with a v3.x client,
but they are not compatible" or with an invalid handshake. There is no
compatibility mode.

**CORS** does not apply: this is not a browser. A server that checks the `Origin`
header in `allowRequest` needs `extraHeaders = mapOf("Origin" to "…")`.

**Missing HTTP stack.** "The polling transport needs an EngineHttpClient; add the
socketio-okhttp module or configure one" means only `socketio-client` is on the
classpath. Add `socketio-okhttp` (JVM) or `socketio-android`.

## Frequent reconnects

**`ping timeout` in the background.** When the app is frozen or the device is in
Doze, the server stops receiving pongs and closes the session. After wake-up the
client detects the expired heartbeat on the next emit, closes once with
`"ping timeout"` and reconnects once. This is expected. Use a `BackgroundPolicy` to
disconnect deliberately, and connection state recovery to resume missed events
([Android](Android.md#doze-and-heartbeats)).

**`ping timeout` in the foreground.** A listener that blocks the executor (no
`callbackDispatcher`) or a proxy that buffers responses delays heartbeats. Check
the server's `pingInterval`/`pingTimeout` and that proxies do not time out idle
WebSockets before `pingInterval`.

**`transport close` on network changes.** Switching Wi-Fi/mobile or toggling a VPN
changes the default network; the Android integration drops the old connection and
reconnects on the new one. This is intended.

**Load balancer without stickiness.** Polling requests reach a node that does not
know the session: `HTTP 400 "Session ID unknown"` followed by a reconnect. Enable
sticky sessions or use `transports = listOf(Transport.WEBSOCKET)`.

## Events are not received

**Listener registered too late.** The manager connects on creation, on another
thread. Events can arrive before a listener added afterwards exists. Register in
the setup block:

```kotlin
val socket = manager.socket("/chat") {
    on("message") { event -> show(event[0]?.string) }
}
```

**Namespace mismatch.** A listener on `manager.socket("/")` does not see events
the server emits to `/chat`.

**Reserved names.** `connect`, `connect_error`, `disconnect`, `disconnecting`,
`newListener` and `removeListener` cannot be emitted; `on` with these names
receives the lifecycle events, not server events.

**Listener registered twice.** The setup block of `manager.socket(nsp) { … }` runs
on every call, also for an existing socket. Calling it repeatedly adds duplicate
listeners. Keep the returned `Socket`, or cancel the `Subscription`s.

**Main thread busy.** With `android(context)`, callbacks queue on the main thread;
a long operation there delays every event.

## Acknowledgements never complete

- Without a timeout, a plain acknowledgement callback is dropped when the socket
  disconnects and never called. Use `socket.timeout(d)` or `ackTimeout`.
- The server handler did not call its callback, or declared it as a different
  parameter.
- `socket.volatile` emits are discarded when the transport is not writable.
- With `retries`, emits are sent one at a time; an unacknowledged first packet holds
  the queue until it times out.

`socket.pendingAcknowledgements` and `socket.pendingEmits` show what is waiting.

## Duplicate events

- `retries` resends an emit whose acknowledgement did not arrive in time; the
  server may have processed the first copy. Include an id and deduplicate on the
  server.
- Duplicate listener registration (see above).
- Emitting from `onConnect` sends again on every reconnect.

## `parse error` disconnects

The server sent a packet that could not be decoded, or one exceeded
`SocketParserOptions` (for example more than 10 binary attachments), or a binary
packet did not complete within `binaryReconstructionTimeout`. The manager
reconnects. `details.error` holds the cause. Mismatched server/client parsers
(for example `socket.io-msgpack-parser` on the server) produce this on every
packet; only the default parser is supported.

## Limits

`SocketBufferLimitException` in an acknowledgement result or `ManagerEvent.Error`
means a configured `SocketBufferLimits` bound was reached; the rejected emit was
not sent. `buffer`, `limit` and `attempted` identify it. A polling response above
`maxPollingResponseBytes` fails with `"xhr poll error"` caused by
`ResponseTooLargeException`.

**Messages above 16 MiB over WebSocket.** OkHttp holds at most 16 MiB of outgoing
WebSocket data and closes the socket beyond that; browsers and Node have no such
limit. The client paces its writes, so many large emits in a row are fine. A
*single* message larger than 16 MiB cannot be sent over OkHttp's WebSocket: the
connection closes with `"websocket error"`, whose cause says *"a message of N bytes
exceeds the WebSocket client's limit of 16777216 bytes"*, and reconnects. Split such
payloads, or use `transports = listOf(Transport.POLLING)` for them (polling is
bounded only by the server's `maxHttpBufferSize`).

## Network binding and VPNs

With `bindToActiveNetwork` (default), connections use Android's default network.
When a VPN is active, that is the VPN. A server on the local Wi-Fi is unreachable if
the default network is mobile data or a VPN that does not route the LAN. Either
ensure the default network can reach it, or set `bindToActiveNetwork = false` and
route the process yourself.

## TLS errors

TLS failures arrive as `TransportException` with the exception as `cause`:

| Cause | Typical reason | Fix |
| --- | --- | --- |
| `SSLHandshakeException` "Trust anchor for certification path not found" | Private CA or self-signed certificate | `TlsPolicy.customTrust(…)` or a Network Security Config trust anchor |
| `SSLPeerUnverifiedException` "Hostname … not verified" | Certificate does not cover the host (for example `10.0.2.2` vs `localhost`) | Issue a certificate with the right subject alternative name |
| `SSLPeerUnverifiedException` "Certificate pinning failure!" | Pins do not match the chain; the message lists the chain's actual pins | Update pins; always ship a backup pin |
| `SSLHandshakeException` / `SSLException` during mutual TLS | No or wrong client certificate, or KeyChain access revoked | Check the `TlsPolicy` client certificate; call `chooseKeyChainAlias` again |

There is no option to disable certificate validation; see
[Configuration](Configuration.md#tls).

## Still stuck

Reproduce with the default options against a minimal Node server, capture the
`packetObserver` output and the `connect_error`/`disconnect` details, and include
the server and client versions when you open an issue.
