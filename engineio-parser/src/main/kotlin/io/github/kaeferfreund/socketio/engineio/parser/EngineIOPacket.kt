package io.github.kaeferfreund.socketio.engineio.parser

/**
 * The Engine.IO 4 packet types.
 *
 * [code] is the single character that prefixes a text-encoded packet on the
 * wire. [ERROR] never appears on the wire; the decoder returns it for input it
 * cannot read, exactly like `engine.io-parser`'s `ERROR_PACKET`.
 */
public enum class EngineIOPacketType(public val code: Char?) {
    OPEN('0'),
    CLOSE('1'),
    PING('2'),
    PONG('3'),
    MESSAGE('4'),
    UPGRADE('5'),
    NOOP('6'),
    ERROR(null),
    ;

    /** The lowercase name `engine.io-parser` uses, for example `"message"`. */
    public val wireName: String get() = name.lowercase()

    public companion object {
        /** The wire type for [code], or `null` when [code] is not one of `0`..`6`. */
        public fun fromCode(code: Char): EngineIOPacketType? =
            when (code) {
                '0' -> OPEN
                '1' -> CLOSE
                '2' -> PING
                '3' -> PONG
                '4' -> MESSAGE
                '5' -> UPGRADE
                '6' -> NOOP
                else -> null
            }
    }
}

/**
 * The payload of an Engine.IO packet: either text or binary.
 *
 * Binary data is held as a defensive copy; [Binary.bytes] returns a fresh copy
 * each time, so a packet can never be mutated after it was created.
 */
public sealed class EngineIOData {
    /** A text payload. */
    public class Text(
        public val value: String,
    ) : EngineIOData() {
        override fun equals(other: Any?): Boolean = other is Text && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Text(${value.length} chars)"
    }

    /** A binary payload. */
    public class Binary private constructor(
        private val content: ByteArray,
        @Suppress("UNUSED_PARAMETER") owned: Boolean,
    ) : EngineIOData() {
        public constructor(bytes: ByteArray) : this(bytes.copyOf(), true)

        /** A copy of the bytes. */
        public val bytes: ByteArray get() = content.copyOf()

        /** Number of bytes, without copying. */
        public val size: Int get() = content.size

        /** Read access without a copy, for codecs in this library. */
        internal fun unsafeBytes(): ByteArray = content

        override fun equals(other: Any?): Boolean = other is Binary && other.content.contentEquals(content)

        override fun hashCode(): Int = content.contentHashCode()

        override fun toString(): String = "Binary($size bytes)"

        public companion object {
            /** Wraps [bytes] without copying. The caller must not modify the array afterwards. */
            public fun wrap(bytes: ByteArray): Binary = Binary(bytes, true)
        }
    }
}

/** Per-packet write options, as `WriteOptions` in `engine.io-client`. */
public class EngineIOPacketOptions(
    /**
     * Whether the packet may be compressed. Only a WebSocket transport with a
     * negotiated `permessage-deflate` extension can act on it.
     */
    public val compress: Boolean = true,
) {
    override fun equals(other: Any?): Boolean = other is EngineIOPacketOptions && other.compress == compress

    override fun hashCode(): Int = compress.hashCode()

    override fun toString(): String = "EngineIOPacketOptions(compress=$compress)"

    public companion object {
        /** `compress = true`, the JavaScript default. */
        public val DEFAULT: EngineIOPacketOptions = EngineIOPacketOptions()
    }
}

/** One Engine.IO packet. */
public class EngineIOPacket(
    public val type: EngineIOPacketType,
    public val data: EngineIOData? = null,
    public val options: EngineIOPacketOptions = EngineIOPacketOptions.DEFAULT,
) {
    /** Convenience constructor for a text payload. */
    public constructor(type: EngineIOPacketType, text: String) : this(type, EngineIOData.Text(text))

    /** The text payload, or `null` when the packet has none or carries binary data. */
    public val text: String? get() = (data as? EngineIOData.Text)?.value

    override fun equals(other: Any?): Boolean = other is EngineIOPacket && other.type == type && other.data == data

    override fun hashCode(): Int = 31 * type.hashCode() + (data?.hashCode() ?: 0)

    override fun toString(): String = "EngineIOPacket(type=${type.wireName}, data=$data)"

    public companion object {
        /** The packet every decoding failure produces: `{ type: "error", data: "parser error" }`. */
        public val PARSER_ERROR: EngineIOPacket = EngineIOPacket(EngineIOPacketType.ERROR, EngineIOData.Text("parser error"))
    }
}
