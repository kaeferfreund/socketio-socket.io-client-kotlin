package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.CloseDetails
import io.github.kaeferfreund.socketio.engineio.EngineIOException
import io.github.kaeferfreund.socketio.engineio.TransportException
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import io.github.kaeferfreund.socketio.parser.SocketIOValue

/**
 * Why a socket disconnected. [wireValue] is the exact JavaScript reason
 * string (`socket.on("disconnect", (reason) => …)`).
 */
public enum class DisconnectReason(
    public val wireValue: String,
) {
    /** The server disconnected the socket (`socket.disconnect()` on the server). */
    IO_SERVER_DISCONNECT("io server disconnect"),

    /** [Socket.disconnect] was called. */
    IO_CLIENT_DISCONNECT("io client disconnect"),

    /** No ping from the server within `pingInterval + pingTimeout`. */
    PING_TIMEOUT("ping timeout"),

    /** The connection was closed (network change, server restart, …). */
    TRANSPORT_CLOSE("transport close"),

    /** The connection failed (for example an HTTP error while polling). */
    TRANSPORT_ERROR("transport error"),

    /** The server sent a packet that could not be decoded. */
    PARSE_ERROR("parse error"),

    /** The manager itself was closed. */
    FORCED_CLOSE("forced close"),
    ;

    /**
     * Whether the manager reconnects on its own after this reason, as in the
     * JavaScript documentation: every reason except a deliberate disconnect
     * by the server or the client.
     */
    public val reconnectsAutomatically: Boolean
        get() = this != IO_SERVER_DISCONNECT && this != IO_CLIENT_DISCONNECT && this != FORCED_CLOSE

    public companion object {
        /** The reason for a JavaScript reason string, or `null`. */
        public fun fromWireValue(value: String): DisconnectReason? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Additional information about a disconnection, `DisconnectDescription` in
 * JavaScript: either the error that closed the connection or the close
 * details of the transport.
 */
public class DisconnectDetails(
    /** For example `"websocket connection closed"` or `"network connection lost"`. */
    public val description: String?,
    /** The error that caused the disconnection, when there was one. */
    public val error: Throwable? = null,
    /** WebSocket close code. */
    public val closeCode: Int? = null,
    /** WebSocket close reason. */
    public val closeReason: String? = null,
) {
    /** HTTP status of a failed polling request, when that caused the disconnection. */
    public val statusCode: Int? get() = (error as? TransportException)?.statusCode

    override fun toString(): String = "DisconnectDetails(description=$description, error=$error, closeCode=$closeCode, closeReason=$closeReason)"

    internal companion object {
        fun from(description: Any?): DisconnectDetails? =
            when (description) {
                null -> null
                is CloseDetails -> DisconnectDetails(description.description, null, description.code, description.reason)
                is Throwable -> DisconnectDetails(description.message, description)
                else -> DisconnectDetails(description.toString())
            }
    }
}

/**
 * A connection error, `connect_error` in JavaScript: the connection could not
 * be established, or a server middleware refused the namespace.
 *
 * [data] carries `err.data` sent by a server middleware.
 */
public class SocketConnectException(
    message: String,
    public val data: SocketIOValue? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** `true` when the server refused the namespace (a CONNECT_ERROR packet); the socket will not retry by itself. */
    public var isServerRefusal: Boolean = false
        internal set
}

/** An acknowledgement did not arrive within its timeout (`"operation has timed out"`). */
public class AckTimeoutException : Exception("operation has timed out")

/** The socket disconnected before the acknowledgement arrived (`"socket has been disconnected"`). */
public class SocketDisconnectedException : Exception("socket has been disconnected")

/** The auth provider threw; the CONNECT packet was not sent. */
public class AuthProviderException(
    cause: Throwable,
) : Exception("the auth provider failed: ${cause.message}", cause)

/**
 * A [SocketBufferLimits] bound was reached. The operation that would have
 * exceeded it failed as a whole; nothing already accepted was evicted.
 */
public class SocketBufferLimitException(
    /** Which buffer. */
    public val buffer: Buffer,
    /** The configured limit. */
    public val limit: Long,
    /** What the buffer would have held. */
    public val attempted: Long,
    /** `true` when [limit] counts bytes, `false` for packets. */
    public val measuringBytes: Boolean,
) : Exception("${buffer.name} limit exceeded: $attempted ${if (measuringBytes) "bytes" else "packets"} would exceed the configured $limit") {
    public enum class Buffer { SEND_BUFFER, RETRY_QUEUE, RECEIVE_BUFFER, BINARY_RECONSTRUCTION }
}

/** Where a connection stands, as published by [Socket.state]. */
public sealed class ConnectionState {
    /** Not connected; [reason] is why the last connection ended (`null` before the first one). */
    public class Disconnected(
        public val reason: DisconnectReason? = null,
        public val details: DisconnectDetails? = null,
    ) : ConnectionState() {
        override fun toString(): String = "Disconnected(${reason?.wireValue})"
    }

    /** Connecting or waiting to reconnect. */
    public object Connecting : ConnectionState() {
        override fun toString(): String = "Connecting"
    }

    /** Connected with namespace session [id]; [recovered] after a successful connection state recovery. */
    public class Connected(
        public val id: String,
        public val recovered: Boolean,
    ) : ConnectionState() {
        override fun toString(): String = "Connected(id=$id, recovered=$recovered)"
    }
}

/** Sends the acknowledgement a server requested for an event. Only the first call has an effect. */
public interface Acknowledgement {
    /** Sends the acknowledgement with [args], converted with [SocketIOValue.of]. */
    public fun send(vararg args: Any?)

    /** Whether [send] was already called. */
    public val isSent: Boolean
}

/** An event received from the server. */
public class IncomingEvent(
    /** The event name; a numeric name is given in its JavaScript string form. */
    public val name: String,
    /** The arguments after the name. */
    public val args: List<SocketIOValue>,
    /** Present when the server expects an acknowledgement. */
    public val ack: Acknowledgement?,
) {
    /** The argument at [index], or `null`. */
    public operator fun get(index: Int): SocketIOValue? = args.getOrNull(index)

    override fun toString(): String = "IncomingEvent(name=$name, args=$args, ack=${ack != null})"
}

/** An event about to be sent, as seen by outgoing listeners. */
public class OutgoingEvent(
    public val name: String,
    public val args: List<SocketIOValue>,
) {
    override fun toString(): String = "OutgoingEvent(name=$name, args=$args)"
}

/** Listener for named events. */
public fun interface EventListener {
    public fun onEvent(event: IncomingEvent)
}

/** Listener for every incoming or outgoing event (`onAny`, `onAnyOutgoing`). */
public fun interface AnyListener {
    public fun onAny(
        name: String,
        args: List<SocketIOValue>,
    )
}

/**
 * Result of an acknowledged emit: the server's arguments, or
 * [AckTimeoutException], [SocketDisconnectedException] or
 * [SocketBufferLimitException].
 *
 * Without a timeout, a disconnection does not fail the callback (it is simply
 * never called), exactly like a plain JavaScript acknowledgement callback.
 */
public fun interface AckCallback {
    public fun onAck(result: Result<List<SocketIOValue>>)
}

/**
 * Supplies the CONNECT payload before every connection attempt, including
 * reconnects — the function form of the JavaScript `auth` option.
 *
 * Return a `Map`, a [SocketIOValue.Object] or `null`. Throwing fails the
 * attempt with an [AuthProviderException] `connect_error`.
 */
public fun interface AuthProvider {
    /** @param attempt the manager's reconnection attempt, `0` for the first connection. */
    public suspend fun provide(attempt: Int): Any?
}

/** Events of a [Socket], as a stream in [Socket.events]. */
public sealed class SocketEvent {
    /** The namespace is connected. */
    public class Connected(
        public val id: String,
        public val recovered: Boolean,
    ) : SocketEvent()

    /** Connecting failed, `connect_error`. */
    public class ConnectError(
        public val error: Throwable,
    ) : SocketEvent()

    /** The socket disconnected. */
    public class Disconnected(
        public val reason: DisconnectReason,
        public val details: DisconnectDetails?,
    ) : SocketEvent()

    /** An event from the server. */
    public class Received(
        public val event: IncomingEvent,
    ) : SocketEvent()
}

/** Events of a [SocketManager] (`ManagerReservedEvents` in JavaScript). */
public sealed class ManagerEvent {
    /** The engine connection is open. */
    public object Open : ManagerEvent()

    /** The engine connection closed, with the reason string of the engine or `"parse error"`/`"forced close"`. */
    public class Close(
        public val reason: DisconnectReason,
        public val details: DisconnectDetails?,
    ) : ManagerEvent()

    /** A connection or transport error. */
    public class Error(
        public val error: Throwable,
    ) : ManagerEvent()

    /** A heartbeat ping from the server. */
    public object Ping : ManagerEvent()

    /** A decoded packet. */
    public class Packet(
        public val packet: SocketIOPacket,
    ) : ManagerEvent()

    /** A reconnection attempt is starting; [attempt] counts from 1. */
    public class ReconnectAttempt(
        public val attempt: Int,
    ) : ManagerEvent()

    /** A reconnection attempt failed. */
    public class ReconnectError(
        public val error: Throwable,
    ) : ManagerEvent()

    /** `reconnectionAttempts` were used up. */
    public object ReconnectFailed : ManagerEvent()

    /** Reconnected successfully after [attempt] attempts. */
    public class Reconnect(
        public val attempt: Int,
    ) : ManagerEvent()
}

internal fun engineError(error: Any?): Throwable =
    when (error) {
        is Throwable -> error
        null -> EngineIOException("unknown error")
        else -> EngineIOException(error.toString())
    }
