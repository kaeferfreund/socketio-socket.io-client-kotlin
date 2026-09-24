package io.github.kaeferfreund.socketio.parser

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows

/** `helpers.test`/`helpers.test_bin` of `socket.io-parser/test/helpers.js`: encode, decode, compare. */
internal fun roundTrip(packet: SocketIOPacket): SocketIOPacket {
    val frames = SocketIOEncoder().encode(packet)
    val decoder = SocketIODecoder()
    var decoded: SocketIOPacket? = null
    for (frame in frames) {
        check(decoded == null) { "decoded before all frames were consumed" }
        decoded = decoder.add(frame)
    }
    assertEquals(packet, decoded)
    return decoded!!
}

internal fun assertParseError(
    expectedMessage: String,
    vararg frames: Any,
) {
    val decoder = SocketIODecoder()
    val error =
        assertThrows<SocketIOParseException> {
            for (frame in frames) {
                when (frame) {
                    is String -> decoder.add(frame)
                    is ByteArray -> decoder.add(frame)
                    is EngineIOData -> decoder.add(frame)
                    else -> error("unsupported frame")
                }
            }
        }
    assertEquals(expectedMessage, error.message)
}

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
