package io.github.kaeferfreund.socketio.engineio.parser

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ports of `packages/engine.io-parser/test/index.ts` and `node.ts`.
 * JavaScript `Buffer`/`ArrayBuffer`/typed arrays all map to `ByteArray`.
 */
class EngineIOParserTest {
    private val message = EngineIOPacketType.MESSAGE

    // JS-283
    @Test
    fun encodesAndDecodesAString() {
        val packet = EngineIOPacket(message, "test")
        val encoded = EngineIOParser.encodePacket(packet, supportsBinary = true)
        assertEquals(EngineIOData.Text("4test"), encoded)
        assertEquals(packet, EngineIOParser.decodePacket(encoded))
    }

    // JS-284
    @Test
    fun failsToDecodeAMalformedPacket() {
        assertEquals(EngineIOPacket.PARSER_ERROR, EngineIOParser.decodePacket(""))
        assertEquals(EngineIOPacket.PARSER_ERROR, EngineIOParser.decodePacket("a123"))
        assertEquals(EngineIOPacketType.ERROR, EngineIOPacket.PARSER_ERROR.type)
        assertEquals("parser error", EngineIOPacket.PARSER_ERROR.text)
    }

    // JS-285
    @Test
    fun encodesAndDecodesAllPacketTypesInAPayload() {
        val packets =
            listOf(
                EngineIOPacket(EngineIOPacketType.OPEN),
                EngineIOPacket(EngineIOPacketType.CLOSE),
                EngineIOPacket(EngineIOPacketType.PING, "probe"),
                EngineIOPacket(EngineIOPacketType.PONG, "probe"),
                EngineIOPacket(message, "test"),
            )
        val payload = EngineIOParser.encodePayload(packets)
        assertEquals("0\u001e1\u001e2probe\u001e3probe\u001e4test", payload)
        assertEquals(packets, EngineIOParser.decodePayload(payload))
        // A packet without data decodes with no data at all, like `{ type: "open" }`.
        assertNull(EngineIOParser.decodePayload(payload)[0].data)
    }

    // JS-286
    @Test
    fun failsToDecodeAMalformedPayload() {
        val error = listOf(EngineIOPacket.PARSER_ERROR)
        assertEquals(error, EngineIOParser.decodePayload("{"))
        assertEquals(error, EngineIOParser.decodePayload("{}"))
        assertEquals(error, EngineIOParser.decodePayload("[\"a123\", \"a456\"]"))
    }

    @Test
    fun decodingAPayloadStopsAtTheFirstErrorPacket() {
        val decoded = EngineIOParser.decodePayload("4a\u001ex\u001e4b")
        assertEquals(listOf(EngineIOPacket(message, "a"), EngineIOPacket.PARSER_ERROR), decoded)
    }

    // JS-302
    @Test
    fun encodesAndDecodesABuffer() {
        val packet = EngineIOPacket(message, EngineIOData.Binary(byteArrayOf(1, 2, 3, 4)))
        val encoded = EngineIOParser.encodePacket(packet, supportsBinary = true)
        assertEquals(packet.data, encoded)
        assertEquals(packet, EngineIOParser.decodePacket(encoded))
    }

    // JS-303
    @Test
    fun encodesAndDecodesABufferAsBase64() {
        val packet = EngineIOPacket(message, EngineIOData.Binary(byteArrayOf(1, 2, 3, 4)))
        val encoded = EngineIOParser.encodePacket(packet, supportsBinary = false)
        assertEquals(EngineIOData.Text("bAQIDBA=="), encoded)
        assertEquals(packet, EngineIOParser.decodePacket(encoded))
    }

    // JS-308
    @Test
    fun decodesARawBinaryFrameAsBinaryMessage() {
        val decoded = EngineIOParser.decodePacket(byteArrayOf(1, 2, 3, 4))
        assertEquals(message, decoded.type)
        val data = decoded.data as EngineIOData.Binary
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), data.bytes)
    }

    // JS-309
    @Test
    fun encodesAndDecodesAStringPlusBufferPayload() {
        val packets =
            listOf(
                EngineIOPacket(message, "test"),
                EngineIOPacket(message, EngineIOData.Binary(byteArrayOf(1, 2, 3, 4))),
            )
        val payload = EngineIOParser.encodePayload(packets)
        assertEquals("4test\u001ebAQIDBA==", payload)
        assertEquals(packets, EngineIOParser.decodePayload(payload))
    }

    @Test
    fun base64DecodingIsAsLenientAsNodeBuffers() {
        // Expected values recorded from Node 24 `Buffer.from(s, "base64")`.
        val cases =
            mapOf(
                "AAEC" to byteArrayOf(0, 1, 2),
                "AAECAw" to byteArrayOf(0, 1, 2, 3),
                "AAECAw==" to byteArrayOf(0, 1, 2, 3),
                "AAE C" to byteArrayOf(0, 1, 2),
                "AA-_" to byteArrayOf(0, 15, 191.toByte()),
                "AA\$EC" to byteArrayOf(0, 1, 2),
                "AAECA" to byteArrayOf(0, 1, 2),
                "A" to byteArrayOf(),
                "AAECAw==AAEC" to byteArrayOf(0, 1, 2, 3),
                "=AAEC" to byteArrayOf(),
                "AA=EC" to byteArrayOf(0),
                "\nAAEC\t" to byteArrayOf(0, 1, 2),
                "////" to byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()),
                "__--" to byteArrayOf(255.toByte(), 255.toByte(), 190.toByte()),
            )
        for ((input, expected) in cases) {
            val packet = EngineIOParser.decodePacket("b$input")
            assertArrayEquals(expected, (packet.data as EngineIOData.Binary).bytes, input)
        }
    }

    @Test
    fun binaryDataIsDefensivelyCopied() {
        val source = byteArrayOf(1, 2, 3)
        val data = EngineIOData.Binary(source)
        source[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), data.bytes)
        data.bytes[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), data.bytes)
    }

    @Test
    fun encodesEveryPacketTypeCode() {
        val expected = listOf('0', '1', '2', '3', '4', '5', '6')
        val types = EngineIOPacketType.entries.filter { it != EngineIOPacketType.ERROR }
        assertEquals(expected, types.map { (EngineIOParser.encodePacket(EngineIOPacket(it), true) as EngineIOData.Text).value.single() })
        for (type in types) {
            assertEquals(type, EngineIOPacketType.fromCode(type.code!!))
        }
        assertTrue(EngineIOPacketType.fromCode('7') == null)
    }
}
