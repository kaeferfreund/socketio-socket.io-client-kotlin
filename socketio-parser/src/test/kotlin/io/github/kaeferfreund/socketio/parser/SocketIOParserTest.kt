package io.github.kaeferfreund.socketio.parser

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Ports of `packages/socket.io-parser/test/parser.js`. */
class SocketIOParserTest {
    // JS-257
    @Test
    fun exposesTypes() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6),
            listOf(
                SocketIOPacketType.CONNECT,
                SocketIOPacketType.DISCONNECT,
                SocketIOPacketType.EVENT,
                SocketIOPacketType.ACK,
                SocketIOPacketType.CONNECT_ERROR,
                SocketIOPacketType.BINARY_EVENT,
                SocketIOPacketType.BINARY_ACK,
            ).map { it.code },
        )
        assertEquals(5, SocketIOPacket.PROTOCOL)
    }

    // JS-258
    @Test
    fun encodesConnection() {
        roundTrip(SocketIOPacket(SocketIOPacketType.CONNECT, "/woot", socketIOObject("token" to "123")))
        assertEquals(
            listOf(EngineIOData.Text("0/woot,{\"token\":\"123\"}")),
            SocketIOEncoder().encode(SocketIOPacket(SocketIOPacketType.CONNECT, "/woot", socketIOObject("token" to "123"))),
        )
    }

    // JS-259
    @Test
    fun encodesDisconnection() {
        roundTrip(SocketIOPacket(SocketIOPacketType.DISCONNECT, "/woot"))
        assertEquals(listOf(EngineIOData.Text("1/woot,")), SocketIOEncoder().encode(SocketIOPacket(SocketIOPacketType.DISCONNECT, "/woot")))
    }

    // JS-260
    @Test
    fun encodesAnEvent() {
        roundTrip(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("a", 1, emptyMap<String, Any>())))
        assertEquals(
            listOf(EngineIOData.Text("2[\"a\",1,{}]")),
            SocketIOEncoder().encode(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("a", 1, emptyMap<String, Any>()))),
        )
    }

    // JS-261
    @Test
    fun encodesAnEventWithAnIntegerAsEventName() {
        roundTrip(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray(1, "a", emptyMap<String, Any>())))
    }

    // JS-262
    @Test
    fun encodesAnEventWithAck() {
        val packet = SocketIOPacket(SocketIOPacketType.EVENT, "/test", socketIOArray("a", 1, emptyMap<String, Any>()), id = 1)
        roundTrip(packet)
        assertEquals(listOf(EngineIOData.Text("2/test,1[\"a\",1,{}]")), SocketIOEncoder().encode(packet))
    }

    // JS-263
    @Test
    fun encodesAnAck() {
        val packet = SocketIOPacket(SocketIOPacketType.ACK, "/", socketIOArray("a", 1, emptyMap<String, Any>()), id = 123)
        roundTrip(packet)
        assertEquals(listOf(EngineIOData.Text("3123[\"a\",1,{}]")), SocketIOEncoder().encode(packet))
    }

    // JS-264
    @Test
    fun encodesAConnectError() {
        roundTrip(SocketIOPacket(SocketIOPacketType.CONNECT_ERROR, "/", SocketIOValue.Text("Unauthorized")))
    }

    // JS-265
    @Test
    fun encodesAConnectErrorWithObject() {
        roundTrip(SocketIOPacket(SocketIOPacketType.CONNECT_ERROR, "/", socketIOObject("message" to "Unauthorized")))
    }

    // JS-266: Kotlin values are immutable trees; cycles can only exist in the
    // Map/List input, and converting them fails like JSON.stringify does.
    @Test
    fun throwsWhenEncodingCircularObjects() {
        val a = HashMap<String, Any?>()
        a["b"] = a
        val error = assertThrows<IllegalArgumentException> { SocketIOValue.of(a) }
        assertTrue(error.message!!.contains("circular"))
        val list = ArrayList<Any?>()
        list.add(listOf(list))
        assertThrows<IllegalArgumentException> { SocketIOValue.of(list) }
    }

    // JS-267
    @Test
    fun decodesABadBinaryPacket() {
        val error = assertThrows<SocketIOParseException> { SocketIODecoder().add("5") }
        assertTrue(error.message!!.contains("Illegal"))
    }

    // JS-268
    @Test
    fun throwsWhenReceivingTooManyAttachments() {
        val decoder = SocketIODecoder(SocketParserOptions(maxAttachments = 2))
        val error =
            assertThrows<SocketIOParseException> {
                decoder.add(
                    "53-[\"hello\",{\"_placeholder\":true,\"num\":0},{\"_placeholder\":true,\"num\":1},{\"_placeholder\":true,\"num\":2}]",
                )
            }
        assertEquals("too many attachments", error.message)
    }

    // JS-269
    @Test
    fun decodesWithACustomReviver() {
        val reviver = { key: String, value: SocketIOValue -> if (key == "a") SocketIOValue.Text(value.string!!.uppercase()) else value }
        val decoder = SocketIODecoder(SocketParserOptions(reviver = reviver))
        val packet = decoder.add("2[\"b\",{\"a\":\"val\"}]")!!
        assertEquals(socketIOArray("b", socketIOObject("a" to "VAL")), packet.data)
    }

    // JS-270: the JavaScript options-object form; Kotlin has a single options class.
    @Test
    fun decodesWithACustomReviverFromTheOptionsObject() {
        val options =
            SocketParserOptions(
                reviver = { key, value -> if (key == "a") SocketIOValue.Text(value.string!!.uppercase()) else value },
            )
        val packet = SocketIODecoder(options).add("2[\"b\",{\"a\":\"val\"}]")!!
        assertEquals(socketIOArray("b", socketIOObject("a" to "VAL")), packet.data)
    }

    // JS-271
    @Test
    fun throwsAnErrorUponParsingError() {
        for (input in listOf(
            "442[\"some\",\"data\"",
            "0/admin,\"invalid\"",
            "0[]",
            "1/admin,{}",
            "2/admin,\"invalid",
            "2/admin,{}",
            "2[{\"toString\":\"foo\"}]",
            "2[true,\"foo\"]",
            "2[null,\"bar\"]",
            "2[\"connect\"]",
            "2[\"disconnect\",\"123\"]",
        )) {
            assertParseError("invalid payload", input)
        }
        for (input in listOf("5", "51", "50-", "5a-", "51.23-")) {
            assertParseError("Illegal attachments", input)
        }
        assertParseError("unknown packet type 9", "999")
        // `decoder.add(999)` ("Unknown type: 999") cannot be written in Kotlin:
        // add() only accepts text or binary, so the type system rejects it.
    }

    // JS-272
    @Test
    fun resumesDecodingAfterDestroy() {
        val decoder = SocketIODecoder()
        assertNull(decoder.add("51-[\"hello\"]"))
        assertTrue(decoder.isReconstructing)
        decoder.destroy()
        assertFalse(decoder.isReconstructing)
        assertEquals(socketIOArray("hello"), decoder.add("2[\"hello\"]")!!.data)
    }

    // JS-273
    @Test
    fun ensuresThatAPacketIsValid() {
        assertTrue(SocketIOPacket(SocketIOPacketType.CONNECT, "/").isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.CONNECT, "/admin", SocketIOValue.Text("invalid")).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.CONNECT, "/", SocketIOValue.EMPTY_ARRAY).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.DISCONNECT, "/admin", SocketIOValue.EMPTY_OBJECT).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.EVENT, "/admin", SocketIOValue.Text("invalid")).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.EVENT, "/admin", SocketIOValue.EMPTY_OBJECT).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOObject("toString" to "foo")).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray(true, "foo")).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray(null, "bar")).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("connect")).isValid())
        assertFalse(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("disconnect", "123")).isValid())
    }

    @Test
    fun decodesThePayloadlessAndCoercedFormsJavaScriptAccepts() {
        val decoder = SocketIODecoder()
        // `charAt(0)` of "" is "" and Number("") is 0: a CONNECT without data.
        assertEquals(SocketIOPacket(SocketIOPacketType.CONNECT, "/"), decoder.add(""))
        assertEquals(SocketIOPacket(SocketIOPacketType.EVENT, "/", id = 123), decoder.add("2123"))
        assertEquals(SocketIOPacket(SocketIOPacketType.EVENT, "/namespace"), decoder.add("2/namespace,"))
        assertEquals(SocketIOPacket(SocketIOPacketType.EVENT, "/é🦧", socketIOArray("x"), 0), decoder.add("2/é🦧,0[\"x\"]"))
        // Number("1e0") is 1, so JavaScript accepts this attachment count.
        assertNull(decoder.add("51e0-[\"x\",{\"_placeholder\":true,\"num\":0}]"))
        assertEquals(socketIOArray("x", SocketIOValue.Binary(bytesOf(7))), decoder.add(bytesOf(7))!!.data)
    }

    @Test
    fun rejectsAckIdsThatAreNotSafeIntegers() {
        assertParseError("invalid ack id", "29999999999999999999999[\"x\"]")
    }

    @Test
    fun enforcesTheOptionalTextAndDepthLimits() {
        val small = SocketIODecoder(SocketParserOptions(maxTextPacketBytes = 8))
        assertEquals(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("é")), small.add("2[\"é\"]"))
        assertThrows<SocketIOParseException> { small.add("2[\"éé\"]") }
        val shallow = SocketIODecoder(SocketParserOptions(maxNestingDepth = 2))
        shallow.add("2[\"a\",[1]]")
        val error = assertThrows<SocketIOParseException> { shallow.add("2[\"a\",[[1]]]") }
        assertTrue(error.message!!.contains("maxNestingDepth"))
    }

    @Test
    fun rejectsInvalidParserOptions() {
        assertThrows<IllegalArgumentException> { SocketParserOptions(maxAttachments = 0) }
        assertThrows<IllegalArgumentException> { SocketParserOptions(maxBinaryPacketBytes = 0) }
        assertThrows<IllegalArgumentException> { SocketParserOptions(maxTextPacketBytes = -1) }
        assertThrows<IllegalArgumentException> { SocketParserOptions(maxNestingDepth = 0) }
    }

    @Test
    fun aThrowingReviverMakesThePayloadInvalidLikeTryParse() {
        val decoder = SocketIODecoder(SocketParserOptions(reviver = { _, _ -> error("boom") }))
        assertEquals("invalid payload", assertThrows<SocketIOParseException> { decoder.add("2[\"a\"]") }.message)
    }

    @Test
    fun encodesBinaryOutsideEventsLikeNodeBufferToJson() {
        val frames = SocketIOEncoder().encode(SocketIOPacket(SocketIOPacketType.CONNECT, "/", socketIOObject("key" to bytesOf(1, 2))))
        assertEquals(listOf(EngineIOData.Text("0{\"key\":{\"type\":\"Buffer\",\"data\":[1,2]}}")), frames)
    }
}
