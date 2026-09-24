# Acknowledgements and delivery

[Documentation index](../README.md) · [Project overview](../../README.md)

Examples assume an existing `socket`; `suspend` examples run in a coroutine. Durations
use `kotlin.time` (`import kotlin.time.Duration.Companion.seconds`).

## Acknowledgements

A Socket.IO acknowledgement lets the server confirm or answer an event. The client
offers two forms: a callback and a `suspend` function. Both deliver the server's
acknowledgement arguments unchanged as `List<SocketIOValue>`; the client never
interprets the first argument as an error.

A timeout means that no acknowledgement arrived in time. It does not prove that the
server did not process the event.

### Callback

Pass an `AckCallback` as the trailing lambda. It receives a
`Result<List<SocketIOValue>>`:

```kotlin
socket.timeout(5.seconds).emit("createOrder", mapOf("item" to "coffee")) { result ->
    result
        .onSuccess { args -> println("Order id: ${args.firstOrNull()?.string}") }
        .onFailure { error ->
            when (error) {
                is AckTimeoutException -> println("No acknowledgement within 5 s")
                is SocketDisconnectedException -> println("Disconnected before the acknowledgement")
                else -> println("Failed: $error")
            }
        }
}
```

The callback runs on the manager's callback thread: the protocol executor in the
core, the main thread with `android(context)`.

### Suspending

`emitWithAck` suspends until the acknowledgement arrives and returns all of its
arguments:

```kotlin
try {
    val args = socket.timeout(5.seconds).emitWithAck("createOrder", mapOf("item" to "coffee"))
    println("Order id: ${args.firstOrNull()?.string}")
} catch (e: AckTimeoutException) {
    println("No acknowledgement within 5 s")
} catch (e: SocketDisconnectedException) {
    println("Disconnected before the acknowledgement")
}
```

`socket.emitWithAck(...)` without `timeout(...)` uses the socket's default
[`ackTimeout`](#default-timeout), or waits without a limit when none is set.

**Cancellation.** Cancelling the calling coroutine (for example when a
`viewModelScope` ends, or through `withTimeout`) withdraws the acknowledgement. If
the event was still waiting in the send buffer, it is removed and never sent. An
event already sent may still be processed by the server. `CancellationException`
propagates as usual.

## Timeouts

### Per emit

`socket.timeout(duration)` returns a `SocketEmitter` whose acknowledgements fail with
`AckTimeoutException` after `duration`. The emitter is immutable and can be reused:

```kotlin
val quick = socket.timeout(2.seconds)

quick.emit("ping") { result -> println(result) }
val pong = quick.emitWithAck("ping")
```

The timeout starts when you call `emit`, not when the packet leaves the device. An
acknowledged emit that times out while still in the send buffer is removed from the
buffer and never sent.

### Default timeout

Set a default for every acknowledged emit on one namespace with `SocketOptions`:

```kotlin
val socket =
    manager.socket("/", SocketOptions { ackTimeout = 10.seconds }) {
        on("message") { event -> println(event.args) }
    }
```

`timeout(...)` on a single emit overrides the default. `SocketOptions` apply when the
socket for a namespace is created; later calls to `manager.socket` for the same
namespace return the existing socket with its original options.

## Behaviour on disconnect

What happens to a pending acknowledgement when the socket disconnects depends on
whether a timeout applies. This matches the JavaScript client.

| Form | Acknowledgement pending when the socket disconnects |
| --- | --- |
| `emit(...) { }` without any timeout | Forgotten: the callback is never called |
| `emit(...) { }` with `timeout(...)` or `ackTimeout` | Fails with `SocketDisconnectedException` |
| `emitWithAck(...)`, with or without timeout | Throws `SocketDisconnectedException` |

Events that were emitted while disconnected and are still in the send buffer are not
affected: they keep their acknowledgement, are sent after the reconnect, and a
timeout keeps running while they wait.

With [retries](#retries), a disconnect counts as a failed try instead: the event is
sent again after reconnecting, and the error is only reported once the tries are
used up.

A plain callback without timeout is therefore only suitable when missing an answer
is acceptable. Use a timeout, or `emitWithAck`, whenever your code waits for the
result.

## Retries

With `retries`, the socket resends unacknowledged events in order:

```kotlin
val socket =
    manager.socket(
        "/orders",
        SocketOptions {
            ackTimeout = 5.seconds
            retries = 3
        },
    )

socket.emit("createOrder", mapOf("id" to orderId, "item" to "coffee")) { result ->
    println(result)
}
```

This means:

- every non-volatile emit on this socket enters an ordered queue, whether or not you
  pass a callback, and asks the server for an acknowledgement;
- one event is in flight at a time; the next one is sent after the current one is
  acknowledged or has given up;
- an event is sent up to `retries + 1` times, each try with a fresh acknowledgement
  id; a timeout or a disconnect counts as a failed try, and the queue resumes after
  reconnecting;
- after the last failed try, your callback receives the last error
  (`AckTimeoutException` or `SocketDisconnectedException`) and the queue moves on;
- the server's acknowledgement arguments are always delivered as a success; unlike
  the JavaScript client without `ackTimeout`, the first argument is never read as
  an error.

The server must acknowledge **every** event of this namespace (call the callback in
each handler). Without `ackTimeout`, an event the server does not acknowledge blocks
the queue until the next disconnect.

**Delivery is at-least-once.** A lost acknowledgement causes the event to be sent
again, so the server may receive it more than once. For operations that are not
idempotent, include an application-level id and deduplicate on the server:

```javascript
io.of("/orders").on("connection", (socket) => {
  socket.on("createOrder", async (order, callback) => {
    if (!(await seen(order.id))) await create(order);
    callback({ ok: true });
  });
});
```

Volatile emits bypass the queue. Cancelling an `emitWithAck` does not remove an
event from the retry queue.

## Acknowledging server requests

When the server emits with a callback, the incoming event carries an
`Acknowledgement`:

```kotlin
socket.on("question") { event ->
    val ack = event.ack ?: return@on
    ack.send("received", mapOf("at" to Instant.now()))
}
```

Arguments are converted like `emit` arguments. Only the first `send` has an effect;
`ack.isSent` tells whether it happened. `send` may be called later and from any
thread, for example after a database lookup in a coroutine:

```kotlin
socket.on("lookup") { event ->
    scope.launch {
        val user = repository.find(event[0]?.string)
        event.ack?.send(user?.name)
    }
}
```

## Observing delivery

`pendingEmits` counts events waiting in the send buffer or the retry queue;
`pendingAcknowledgements` counts acknowledgements still expected. Both are
`StateFlow<Int>`:

```kotlin
lifecycleScope.launch {
    socket.pendingEmits.collect { count -> showUnsentBadge(count) }
}
```

`sendBufferSnapshot()` returns the buffered events themselves.

An `OutgoingInterceptor` is told what happens to each emit, for example to persist
unsent work with WorkManager:

```kotlin
val socket =
    manager.socket(
        "/",
        SocketOptions {
            outgoingInterceptor =
                object : OutgoingInterceptor {
                    override fun onBuffered(event: OutgoingEvent) {
                        store.save(event.name, event.args)
                    }

                    override fun onSent(event: OutgoingEvent) {
                        store.remove(event.name, event.args)
                    }

                    override fun onDropped(event: OutgoingEvent, reason: Throwable?) {
                        println("Dropped ${event.name}: $reason")
                    }
                }
        },
    )
```

| Callback | When |
| --- | --- |
| `onBuffered` | The emit was buffered because the socket is not connected |
| `onSent` | The emit was handed to the transport |
| `onDropped` | Volatile without a writable transport, timed out while buffered, or over a [buffer limit](Configuration.md) (`SocketBufferLimitException`) |

The interceptor runs on the protocol executor; keep it fast.
