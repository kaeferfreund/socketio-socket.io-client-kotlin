package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import kotlin.time.Duration

/** The validated Engine.IO handshake, `HandshakeData` in JavaScript. */
public class Handshake(
    public val sid: String,
    public val upgrades: List<String>,
    public val pingInterval: Duration,
    public val pingTimeout: Duration,
    /** Maximum polling payload bytes; `null` when the server sent none. */
    public val maxPayload: Long?,
) {
    override fun toString(): String = "Handshake(sid=$sid, upgrades=$upgrades, pingInterval=$pingInterval, pingTimeout=$pingTimeout, maxPayload=$maxPayload)"
}

/** Reasons the engine closes, as the JavaScript strings. */
public object EngineCloseReason {
    public const val FORCED_CLOSE: String = "forced close"
    public const val PING_TIMEOUT: String = "ping timeout"
    public const val TRANSPORT_CLOSE: String = "transport close"
    public const val TRANSPORT_ERROR: String = "transport error"
}

/** Events of an [EngineSocket], the reserved events of `engine.io-client`'s `Socket`. */
public sealed class EngineEvent {
    public object Open : EngineEvent()

    public class HandshakeReceived(
        public val handshake: Handshake,
    ) : EngineEvent()

    public class PacketReceived(
        public val packet: EngineIOPacket,
    ) : EngineEvent()

    public class PacketCreated(
        public val packet: EngineIOPacket,
    ) : EngineEvent()

    /** A message's payload; `data` and `message` are the same event in JavaScript. */
    public class Message(
        public val data: EngineIOData,
    ) : EngineEvent()

    public object Drain : EngineEvent()

    public object Flush : EngineEvent()

    public object Heartbeat : EngineEvent()

    public object Ping : EngineEvent()

    public object Pong : EngineEvent()

    public class Error(
        public val error: EngineIOException,
    ) : EngineEvent()

    public class Upgrading(
        public val transport: EngineTransport,
    ) : EngineEvent()

    public class Upgrade(
        public val transport: EngineTransport,
    ) : EngineEvent()

    public class UpgradeError(
        public val error: EngineIOException,
        /** Name of the transport whose probe failed. */
        public val transportName: String,
    ) : EngineEvent()

    public class Close(
        /** One of [EngineCloseReason]. */
        public val reason: String,
        /** A [CloseDetails] or the [EngineIOException] that caused the close. */
        public val description: Any?,
    ) : EngineEvent()
}
