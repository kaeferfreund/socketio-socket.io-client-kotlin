package io.github.kaeferfreund.socketio.parser

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Ports of `packages/socket.io-parser/test/buffer.js`, `arraybuffer.js` and
 * `blob.js`. Node `Buffer`, `ArrayBuffer`, typed arrays and browser `Blob`
 * all correspond to [SocketIOValue.Binary] (from `ByteArray`/`ByteBuffer`).
 */
class SocketIOBinaryTest {
    private fun abc() = "abc".encodeToByteArray()

    // JS-249
    @Test
    fun encodesABuffer() {
        roundTrip(SocketIOPacket(SocketIOPacketType.EVENT, "/cool", socketIOArray("a", abc()), id = 23))
    }

    // JS-250
    @Test
    fun encodesANestedBuffer() {
        roundTrip(
            SocketIOPacket(SocketIOPacketType.EVENT, "/cool", socketIOArray("a", socketIOObject("b" to listOf("c", abc()))), id = 23),
        )
    }

    // JS-251
    @Test
    fun encodesABinaryAckWithBuffer() {
        roundTrip(
            SocketIOPacket(SocketIOPacketType.ACK, "/back", socketIOArray("a", "xxx".encodeToByteArray(), emptyMap<String, Any>()), id = 127),
        )
    }

    // JS-253
    @Test
    fun throwsWhenAnAttachmentPlaceholderHasAStringNum() {
        assertParseError("illegal attachments", "51-[\"hello\",{\"_placeholder\":true,\"num\":\"splice\"}]", "world".encodeToByteArray())
    }

    // JS-254
    @Test
    fun throwsWhenAnAttachmentPlaceholderIsOutOfBounds() {
        assertParseError("illegal attachments", "51-[\"hello\",{\"_placeholder\":true,\"num\":1}]", "world".encodeToByteArray())
    }

    // JS-255
    @Test
    fun throwsWhenAddingAnAttachmentWithoutHeader() {
        assertParseError("got binary data when not reconstructing a packet", "world".encodeToByteArray())
    }

    // JS-256
    @Test
    fun throwsWhenDecodingABinaryEventWithoutAttachments() {
        assertParseError("got plaintext data when reconstructing a packet", "51-[\"hello\",{\"_placeholder\":true,\"num\":0}]", "2[\"hello\"]")
    }

    // JS-240
    @Test
    fun encodesAnArrayBuffer() {
        roundTrip(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("a", ByteArray(2)), id = 0))
    }

    // JS-242: a Uint8Array view is a ByteBuffer slice in Kotlin.
    @Test
    fun encodesATypedArray() {
        val array = ByteArray(5) { it.toByte() }
        val packet = SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("a", java.nio.ByteBuffer.wrap(array)), id = 0)
        val decoded = roundTrip(packet)
        assertArrayEquals(array, decoded.arguments[0].bytes)
        // Only the remaining bytes of a buffer view are sent, like a typed array's byteOffset/byteLength.
        val view = java.nio.ByteBuffer.wrap(array, 1, 2)
        assertArrayEquals(bytesOf(1, 2), SocketIOValue.of(view).bytes)
    }

    // JS-243
    @Test
    fun encodesArrayBuffersDeepInJson() {
        roundTrip(
            SocketIOPacket(
                SocketIOPacketType.EVENT,
                "/deep",
                socketIOArray(
                    "a",
                    socketIOObject(
                        "a" to "hi",
                        "b" to mapOf("why" to ByteArray(3)),
                        "c" to mapOf("a" to "bye", "b" to mapOf("a" to ByteArray(6))),
                    ),
                ),
                id = 999,
            ),
        )
    }

    // JS-244
    @Test
    fun encodesDeepBinaryJsonWithNullValues() {
        roundTrip(
            SocketIOPacket(
                SocketIOPacketType.EVENT,
                "/",
                socketIOArray("a", socketIOObject("a" to "b", "c" to 4, "e" to mapOf("g" to null), "h" to ByteArray(9))),
                id = 600,
            ),
        )
    }

    // JS-245
    @Test
    fun doesNotModifyTheInputPacket() {
        val packet = SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("a", bytesOf(1, 2, 3), bytesOf(4, 5, 6)))
        val before = SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("a", bytesOf(1, 2, 3), bytesOf(4, 5, 6)))
        val frames = SocketIOEncoder().encode(packet)
        assertEquals(before, packet)
        assertEquals(
            EngineIOData.Text("52-[\"a\",{\"_placeholder\":true,\"num\":0},{\"_placeholder\":true,\"num\":1}]"),
            frames[0],
        )
        assertEquals(listOf(EngineIOData.Binary(bytesOf(1, 2, 3)), EngineIOData.Binary(bytesOf(4, 5, 6))), frames.drop(1))
    }

    // JS-246
    @Test
    fun encodesABlob() {
        roundTrip(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("a", ByteArray(2)), id = 0))
    }

    // JS-247
    @Test
    fun encodesABlobDeepInJson() {
        roundTrip(
            SocketIOPacket(
                SocketIOPacketType.EVENT,
                "/deep",
                socketIOArray("a", socketIOObject("a" to "hi", "b" to mapOf("why" to ByteArray(2)), "c" to "bye")),
                id = 999,
            ),
        )
    }

    // JS-248
    @Test
    fun encodesABinaryAckWithABlob() {
        roundTrip(
            SocketIOPacket(
                SocketIOPacketType.ACK,
                "/deep",
                socketIOArray(socketIOObject("a" to "hi ack", "b" to mapOf("why" to ByteArray(2)), "c" to "bye ack")),
                id = 999,
            ),
        )
    }

    @Test
    fun numbersAttachmentsInJavaScriptTraversalOrder() {
        // Integer-like keys come first in JavaScript objects, so "1" is visited before "a".
        val data = socketIOArray("e", socketIOObject("a" to bytesOf(1), "1" to bytesOf(2)), bytesOf(3))
        val frames = SocketIOEncoder().encode(SocketIOPacket(SocketIOPacketType.EVENT, "/", data))
        assertEquals(
            EngineIOData.Text(
                "53-[\"e\",{\"1\":{\"_placeholder\":true,\"num\":0},\"a\":{\"_placeholder\":true,\"num\":1}},{\"_placeholder\":true,\"num\":2}]",
            ),
            frames[0],
        )
        assertEquals(listOf(bytesOf(2), bytesOf(1), bytesOf(3)).map { EngineIOData.Binary(it) }, frames.drop(1))
    }

    @Test
    fun enforcesTheOptionalBinaryPacketLimit() {
        val decoder = SocketIODecoder(SocketParserOptions(maxBinaryPacketBytes = 4))
        decoder.add("52-[\"x\",{\"_placeholder\":true,\"num\":0},{\"_placeholder\":true,\"num\":1}]")
        decoder.add(bytesOf(1, 2, 3))
        assertEquals(
            "binary packet exceeds maxBinaryPacketBytes (4)",
            org.junit.jupiter.api.assertThrows<SocketIOParseException> { decoder.add(bytesOf(4, 5)) }.message,
        )
    }
}
