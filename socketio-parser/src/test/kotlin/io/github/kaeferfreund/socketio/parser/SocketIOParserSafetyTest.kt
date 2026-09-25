package io.github.kaeferfreund.socketio.parser

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Peer input is hostile: no packet may make the decoder throw anything but
 * [SocketIOParseException], and valid packets must round-trip.
 */
@OptIn(ExperimentalKotest::class)
class SocketIOParserSafetyTest {
    /** The seeded generator and alphabet of the Swift fork's `test-parser-safety.sh`. */
    @Test
    fun twentyThousandSeededMalformedInputsOnlyEverFailWithParseErrors() {
        val alphabet = "0123456789abcdef/-_,:\"[]{} nulltrue🦧é".codePoints().toArray().map { String(Character.toChars(it)) }
        var state = 0x51234UL
        var decoded = 0
        repeat(20_000) {
            state = state * 6364136223846793005UL + 1442695040888963407UL
            val size = ((state shr 32) % 96UL).toInt()
            val text = StringBuilder()
            repeat(size) {
                state = state * 6364136223846793005UL + 1442695040888963407UL
                text.append(alphabet[((state shr 32) % alphabet.size.toULong()).toInt()])
            }
            val decoder = SocketIODecoder()
            try {
                var packet = decoder.add(text.toString())
                var guard = 0
                while (packet == null && decoder.isReconstructing && guard++ < 10) packet = decoder.add(bytesOf(1))
                if (packet != null) {
                    decoded++
                    packet.arguments
                    packet.eventName
                    packet.toString()
                }
            } catch (expected: SocketIOParseException) {
                // the only permitted failure
            }
        }
        assert(decoded > 0)
    }

    @Test
    fun knownMalformedHeadersAreRejected() {
        for (message in listOf(
            "5", "6", "51", "50-[\"x\"]", "5a-", "51.23-", "999", "442[\"some\",\"data\"", "0/admin,\"invalid\"", "0[]", "1[]",
            "1/admin,{}", "2/admin,\"invalid", "2/admin,{}", "2[]", "3{}", "2[{\"toString\":\"foo\"}]", "2[true,\"foo\"]", "2[null,\"bar\"]",
            "2[\"connect\"]", "2[\"disconnect\",\"123\"]", "59999999999999999999999-[\"x\"]", "7", "x", "🦧",
        )) {
            val threw =
                try {
                    SocketIODecoder().add(message)
                    false
                } catch (expected: SocketIOParseException) {
                    true
                }
            assert(threw) { "accepted invalid packet $message" }
        }
        // Placeholder guards need the attachment to arrive first.
        for (header in listOf(
            "51-[\"x\",{\"_placeholder\":true,\"num\":99}]",
            "51-[\"x\",{\"_placeholder\":true,\"num\":-1}]",
            "51-[\"x\",{\"_placeholder\":true}]",
            "51-[\"x\",{\"_placeholder\":true,\"num\":0.5}]",
        )) {
            val decoder = SocketIODecoder()
            decoder.add(header)
            org.junit.jupiter.api.assertThrows<SocketIOParseException> { decoder.add(bytesOf(1)) }
        }
    }

    @Test
    fun aHugeDeclaredAttachmentCountIsNotAllocatedUpFront() {
        // With the limit raised to "unlimited", the declared count alone must not reserve memory.
        val decoder = SocketIODecoder(SocketParserOptions(maxAttachments = Int.MAX_VALUE))
        assertEquals(null, decoder.add("52147483647-[\"x\",{\"_placeholder\":true,\"num\":0}]"))
        assertEquals(Int.MAX_VALUE, decoder.missingAttachments)
        assertEquals(null, decoder.add(byteArrayOf(1)))
        assertEquals(Int.MAX_VALUE - 1, decoder.missingAttachments)
    }

    @Test
    fun aBinaryHeaderWithoutPayloadWaitsLikeJavaScriptAndYieldsAnEmptyEvent() {
        val decoder = SocketIODecoder()
        assertEquals(null, decoder.add("51-"))
        assertEquals(SocketIOPacket(SocketIOPacketType.EVENT, "/"), decoder.add(bytesOf(1)))
    }

    @Test
    fun randomTextNeverEscapesAsAnotherException() =
        runTest {
            checkAll(5_000, PropTestConfig(seed = 1234), Arb.string(0..64)) { text ->
                try {
                    SocketIODecoder().add(text)
                } catch (expected: SocketIOParseException) {
                    // fine
                }
            }
        }

    @Test
    fun randomValuesRoundTripThroughEncoderAndDecoder() =
        runTest {
            checkAll(2_000, PropTestConfig(seed = 42), valueArb, Arb.int(0..3)) { value, variant ->
                val nsp = listOf("/", "/foo", "/é🦧", "/x-y_z")[variant]
                val id = if (variant % 2 == 0) null else variant.toLong() * 1000
                val event = SocketIOPacket(SocketIOPacketType.EVENT, nsp, socketIOArray("event", value), id)
                val ack = SocketIOPacket(SocketIOPacketType.ACK, nsp, socketIOArray(value), variant.toLong())
                for (packet in listOf(event, ack)) {
                    val decoder = SocketIODecoder(SocketParserOptions(maxAttachments = Int.MAX_VALUE))
                    var decoded: SocketIOPacket? = null
                    for (frame in SocketIOEncoder().encode(packet)) decoded = decoder.add(frame)
                    assertEquals(packet, decoded)
                }
            }
        }

    /**
     * The protocol cannot distinguish application data shaped like a
     * placeholder from a real one inside a binary packet; JavaScript fails in
     * the same way. Outside binary packets such objects are plain data.
     */
    @Test
    fun applicationPlaceholderLookalikesBehaveLikeJavaScript() {
        val lookalike = socketIOObject("_placeholder" to true, "num" to 5)
        val plain = SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("e", lookalike))
        assertEquals(plain, SocketIODecoder().add(SocketIOEncoder().encode(plain).single()))
        val frames = SocketIOEncoder().encode(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("e", lookalike, bytesOf(1))))
        val decoder = SocketIODecoder()
        decoder.add(frames[0])
        org.junit.jupiter.api.assertThrows<SocketIOParseException> { decoder.add(frames[1]) }
    }

    @Test
    fun encoderOutputIsAlwaysDecodable() {
        val frames: List<EngineIOData> = SocketIOEncoder().encode(SocketIOPacket(SocketIOPacketType.EVENT, "/", socketIOArray("x", Double.NaN)))
        // NaN is written as null, as JSON.stringify does.
        assertEquals(EngineIOData.Text("2[\"x\",null]"), frames.single())
    }

    private companion object {
        val scalars =
            listOf<Any?>(null, true, false, -1, 0, 5, 12345, 1.5, "", "a", "é🦧", "\"[,]\\", Long.MIN_VALUE, 1e300, -2.5e-10, "\u0000\u2028")

        val valueArb: Arb<SocketIOValue> =
            arbitrary { rs -> SocketIOValue.of(randomValue(rs, 0)) }

        fun randomValue(
            rs: RandomSource,
            depth: Int,
        ): Any? {
            val r = rs.random
            return when {
                depth > 4 || r.nextInt(4) < 2 -> if (r.nextInt(5) == 0) ByteArray(r.nextInt(6)) { it.toByte() } else scalars[r.nextInt(scalars.size)]

                r.nextBoolean() -> List(r.nextInt(4)) { randomValue(rs, depth + 1) }

                else -> (0 until r.nextInt(4)).associate {
                    listOf("x", "1", "0", "🦧", "placeholder", "num", "k$it")[r.nextInt(7)] to randomValue(rs, depth + 1)
                }
            }
        }
    }
}
