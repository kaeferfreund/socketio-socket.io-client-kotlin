package io.github.kaeferfreund.socketio.parser

/** Socket.IO protocol 5 packet types, `PacketType` in `socket.io-parser`. */
public enum class SocketIOPacketType(
    public val code: Int,
) {
    CONNECT(0),
    DISCONNECT(1),
    EVENT(2),
    ACK(3),
    CONNECT_ERROR(4),
    BINARY_EVENT(5),
    BINARY_ACK(6),
    ;

    public companion object {
        /** The type for [code], or `null` outside `0..6`. */
        public fun fromCode(code: Int): SocketIOPacketType? = entries.getOrNull(code)
    }
}

/**
 * One Socket.IO packet.
 *
 * [data] is an [SocketIOValue.Array] for events and acknowledgements, an
 * [SocketIOValue.Object] (or `null`) for CONNECT, a [SocketIOValue.Text] or
 * [SocketIOValue.Object] for CONNECT_ERROR and `null` for DISCONNECT. [id] is
 * the acknowledgement id, when there is one.
 */
public class SocketIOPacket(
    public val type: SocketIOPacketType,
    public val nsp: String = "/",
    public val data: SocketIOValue? = null,
    public val id: Long? = null,
) {
    /** The event name of an EVENT packet (a string or a number), else `null`. */
    public val eventName: SocketIOValue? get() = if (type == SocketIOPacketType.EVENT ||
        type == SocketIOPacketType.BINARY_EVENT
    ) {
        (data as? SocketIOValue.Array)?.get(0)
    } else {
        null
    }

    /** The arguments of an event (after the name) or of an acknowledgement. */
    public val arguments: List<SocketIOValue>
        get() {
            val items = (data as? SocketIOValue.Array)?.items ?: return emptyList()
            return when (type) {
                SocketIOPacketType.EVENT, SocketIOPacketType.BINARY_EVENT -> items.drop(1)
                SocketIOPacketType.ACK, SocketIOPacketType.BINARY_ACK -> items
                else -> emptyList()
            }
        }

    /**
     * Whether this packet could legally be sent, `isPacketValid` in
     * `socket.io-parser`: the namespace is a string, the id an integer and
     * the payload matches the type.
     */
    public fun isValid(): Boolean = isDataValid(type, data)

    override fun equals(other: Any?): Boolean =
        other is SocketIOPacket && other.type == type && other.nsp == nsp && other.data == data && other.id == id

    override fun hashCode(): Int = ((type.hashCode() * 31 + nsp.hashCode()) * 31 + (data?.hashCode() ?: 0)) * 31 + (id?.hashCode() ?: 0)

    override fun toString(): String = "SocketIOPacket(type=$type, nsp=$nsp, id=$id, data=$data)"

    public companion object {
        /** Event names with a special meaning, which may be neither sent nor received. */
        public val RESERVED_EVENTS: Set<String> =
            setOf("connect", "connect_error", "disconnect", "disconnecting", "newListener", "removeListener")

        /** The Socket.IO protocol revision, `protocol` in `socket.io-parser`. */
        public const val PROTOCOL: Int = 5

        internal fun isDataValid(
            type: SocketIOPacketType,
            payload: SocketIOValue?,
        ): Boolean =
            when (type) {
                SocketIOPacketType.CONNECT -> payload == null || payload is SocketIOValue.Object

                SocketIOPacketType.DISCONNECT -> payload == null

                SocketIOPacketType.EVENT -> isEventPayloadValid(payload)

                SocketIOPacketType.ACK -> payload is SocketIOValue.Array

                SocketIOPacketType.CONNECT_ERROR -> payload is SocketIOValue.Text || payload is SocketIOValue.Object

                // isPacketValid rejects the binary types; they only exist on the wire.
                SocketIOPacketType.BINARY_EVENT, SocketIOPacketType.BINARY_ACK -> false
            }

        internal fun isEventPayloadValid(payload: SocketIOValue?): Boolean {
            if (payload !is SocketIOValue.Array) return false
            return when (val first = payload[0]) {
                is SocketIOValue.Number -> true
                is SocketIOValue.Text -> first.value !in RESERVED_EVENTS
                else -> false
            }
        }
    }
}
