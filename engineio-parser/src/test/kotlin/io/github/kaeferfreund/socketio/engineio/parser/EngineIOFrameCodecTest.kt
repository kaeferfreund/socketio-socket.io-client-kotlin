package io.github.kaeferfreund.socketio.engineio.parser

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Ports of the `createPacketEncoderStream`/`createPacketDecoderStream` cases in
 * `packages/engine.io-parser/test/index.ts` and `node.ts`. The framing is a
 * plain byte codec here; JavaScript uses it for WebTransport.
 */
class EngineIOFrameCodecTest {
    private val message = EngineIOPacketType.MESSAGE

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // JS-287
    @Test
    fun encodesAPlaintextPacket() {
        val (header, payload) = EngineIOParser.encodeFrame(EngineIOPacket(message, "1€"))
        assertArrayEquals(bytes(5), header)
        assertArrayEquals(bytes(52, 49, 226, 130, 172), payload)
    }

    // JS-288, JS-289, JS-310
    @Test
    fun encodesABinaryPacket() {
        val (header, payload) = EngineIOParser.encodeFrame(EngineIOPacket(message, EngineIOData.Binary(bytes(1, 2, 3))))
        assertArrayEquals(bytes(131), header)
        assertArrayEquals(bytes(1, 2, 3), payload)
    }

    // JS-290: a Uint16Array [1, 2, 257] is the little-endian bytes 1,0,2,0,1,1.
    @Test
    fun encodesABinaryPacketOfSixteenBitValues() {
        val littleEndian = bytes(1, 0, 2, 0, 1, 1)
        val (header, payload) = EngineIOParser.encodeFrame(EngineIOPacket(message, EngineIOData.Binary(littleEndian)))
        assertArrayEquals(bytes(134), header)
        assertArrayEquals(littleEndian, payload)
    }

    // JS-291
    @Test
    fun encodesAMediumBinaryPacket() {
        val (header, payload) = EngineIOParser.encodeFrame(EngineIOPacket(message, EngineIOData.Binary(ByteArray(12345))))
        assertArrayEquals(bytes(254, 48, 57), header)
        assertEquals(12345, payload.size)
    }

    // JS-292
    @Test
    fun encodesABigBinaryPacket() {
        val data = ByteArray(123456789)
        val (header, payload) = EngineIOParser.encodeFrame(EngineIOPacket(message, EngineIOData.Binary.wrap(data)))
        assertArrayEquals(bytes(255, 0, 0, 0, 0, 7, 91, 205, 21), header)
        assertEquals(123456789, payload.size)
    }

    // JS-293
    @Test
    fun decodesAPlaintextPacket() {
        val decoder = EngineIOFrameDecoder(1_000_000)
        assertEquals(emptyList<EngineIOPacket>(), decoder.push(bytes(5)))
        assertEquals(listOf(EngineIOPacket(message, "1€")), decoder.push(bytes(52, 49, 226, 130, 172)))
    }

    // JS-294
    @Test
    fun decodesPlaintextPacketsByteByByte() {
        val decoder = EngineIOFrameDecoder(1_000_000)
        val out = ArrayList<EngineIOPacket>()
        for (b in listOf(5, 52, 49, 226, 130, 172, 1, 50, 1, 51)) out += decoder.push(bytes(b))
        assertEquals(
            listOf(EngineIOPacket(message, "1€"), EngineIOPacket(EngineIOPacketType.PING), EngineIOPacket(EngineIOPacketType.PONG)),
            out,
        )
    }

    // JS-295
    @Test
    fun decodesPlaintextPacketsAllAtOnce() {
        val decoder = EngineIOFrameDecoder(1_000_000)
        assertEquals(
            listOf(EngineIOPacket(message, "1€"), EngineIOPacket(EngineIOPacketType.PING), EngineIOPacket(EngineIOPacketType.PONG)),
            decoder.push(bytes(5, 52, 49, 226, 130, 172, 1, 50, 1, 51)),
        )
    }

    // JS-296, JS-311
    @Test
    fun decodesABinaryPacket() {
        val packets = EngineIOFrameDecoder(1_000_000).push(bytes(131, 1, 2, 3))
        assertEquals(1, packets.size)
        assertEquals(message, packets[0].type)
        assertArrayEquals(bytes(1, 2, 3), (packets[0].data as EngineIOData.Binary).bytes)
    }

    // JS-297
    @Test
    fun decodesAMediumBinaryPacket() {
        val decoder = EngineIOFrameDecoder(1_000_000)
        val payload = ByteArray(12345) { (it % 251).toByte() }
        decoder.push(bytes(254))
        decoder.push(bytes(48, 57))
        val packets = decoder.push(payload)
        assertArrayEquals(payload, (packets.single().data as EngineIOData.Binary).bytes)
    }

    // JS-298
    @Test
    fun decodesABigBinaryPacket() {
        val decoder = EngineIOFrameDecoder(10_000_000_000)
        val payload = ByteArray(123456789)
        payload[payload.size - 1] = 7
        decoder.push(bytes(255))
        decoder.push(bytes(0, 0, 0, 0, 7, 91, 205, 21))
        val data = decoder.push(payload).single().data as EngineIOData.Binary
        assertEquals(123456789, data.size)
        assertEquals(7, data.bytes[123456788].toInt())
    }

    // JS-299
    @Test
    fun returnsAnErrorPacketWhenThePayloadIsTooBig() {
        assertEquals(listOf(EngineIOPacket.PARSER_ERROR), EngineIOFrameDecoder(10).push(bytes(11)))
    }

    // JS-300
    @Test
    fun returnsAnErrorPacketWhenThePayloadLengthIsInvalid() {
        assertEquals(listOf(EngineIOPacket.PARSER_ERROR), EngineIOFrameDecoder(1_000_000).push(bytes(0)))
    }

    // JS-301
    @Test
    fun returnsAnErrorPacketWhenTheLengthExceedsTheSafeIntegerRange() {
        assertEquals(
            listOf(EngineIOPacket.PARSER_ERROR),
            EngineIOFrameDecoder(1_000_000).push(bytes(255, 1, 0, 0, 0, 0, 0, 0, 0, 0)),
        )
    }

    @Test
    fun roundTripsFramesAcrossArbitraryChunkBoundaries() {
        val packets =
            listOf(
                EngineIOPacket(message, "hello"),
                EngineIOPacket(message, EngineIOData.Binary(ByteArray(300) { it.toByte() })),
                EngineIOPacket(EngineIOPacketType.PING),
                EngineIOPacket(message, "x".repeat(70_000)),
            )
        val stream = packets.flatMap { EngineIOParser.encodeFrame(it) }.fold(ByteArray(0)) { acc, b -> acc + b }
        for (chunkSize in listOf(1, 2, 7, 128, 4096, stream.size)) {
            val decoder = EngineIOFrameDecoder(1_000_000)
            val out = stream.asList().chunked(chunkSize).flatMap { decoder.push(it.toByteArray()) }
            assertEquals(packets, out, "chunk size $chunkSize")
        }
    }

    @Test
    fun staysFailedAfterAFramingError() {
        val decoder = EngineIOFrameDecoder(10)
        assertEquals(listOf(EngineIOPacket.PARSER_ERROR), decoder.push(bytes(11)))
        assertEquals(emptyList<EngineIOPacket>(), decoder.push(bytes(1, 50)))
    }
}
