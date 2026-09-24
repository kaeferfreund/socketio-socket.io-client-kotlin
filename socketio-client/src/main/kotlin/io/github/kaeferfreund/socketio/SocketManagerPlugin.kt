package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.Cancellable

/**
 * Extends a manager with platform integration, for example the Android
 * module's network and lifecycle handling. [attach] runs once when the
 * manager is created, before it connects; the returned handle is cancelled
 * when the manager is closed.
 */
public fun interface SocketManagerPlugin {
    public fun attach(manager: SocketManager): Cancellable
}

/**
 * Receives timing sections for tracing tools such as Android's Perfetto
 * (`androidx.tracing`). Sections are asynchronous: they begin and end in
 * different callbacks, matched by [cookie].
 *
 * Section names: `socket.io connect` (engine open attempt), `socket.io
 * upgrade` (WebSocket probe to switch) and `socket.io ack <event>` (emit to
 * acknowledgement).
 */
public interface SocketTracer {
    public fun beginAsyncSection(
        name: String,
        cookie: Int,
    )

    public fun endAsyncSection(
        name: String,
        cookie: Int,
    )
}
