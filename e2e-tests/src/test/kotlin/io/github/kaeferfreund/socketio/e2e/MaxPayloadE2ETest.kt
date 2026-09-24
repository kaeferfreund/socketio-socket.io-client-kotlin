package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.SocketOptions
import io.github.kaeferfreund.socketio.Transport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Large messages close to the server's `maxHttpBufferSize` (the handshake's
 * `maxPayload`) over polling and WebSocket: batches stay under the limit and
 * nothing is lost (KT-PAYLOAD). Uses the Swift fork's `server.js`.
 */
class MaxPayloadE2ETest {
    @Test
    fun largeMessagesNearMaxPayloadArriveIntactOnEveryTransport() {
        for (transports in listOf(listOf(Transport.POLLING), listOf(Transport.WEBSOCKET))) {
            e2e("server.js", mapOf("MAX_HTTP_BUFFER_SIZE" to "20000")) {
                val socket = manager { this.transports = transports }.socket("/", SocketOptions { ackTimeout = kotlin.time.Duration.parse("10s") })
                socket.awaitConnect()
                // Each echo payload is ~9 KB; two would exceed 20 KB in one polling POST, so they must be split.
                val payloads = List(6) { index -> "$index-" + "x".repeat(9_000) }
                val replies =
                    kotlinx.coroutines.coroutineScope {
                        payloads.map { payload -> async { socket.emitWithAck("echo", payload)[0].string } }.awaitAll()
                    }
                assertEquals(payloads, replies)
                assertEquals(true, socket.connected)
                socket.disconnect()
            }
        }
    }
}
