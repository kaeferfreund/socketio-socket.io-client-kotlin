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

    // socket.io-client-java#773 and #726: many large emits in a burst. OkHttp closes a WebSocket
    // whose outgoing queue exceeds 16 MiB, a limit browsers and Node do not have; the JavaScript
    // client sends all of them. The transport must not overfill OkHttp's queue.
    @Test
    fun aBurstOfLargeEmitsOverWebSocketArrivesWithoutClosingTheConnection() =
        e2e("server.js", mapOf("MAX_HTTP_BUFFER_SIZE" to "100000000")) {
            val socket = manager { transports = listOf(Transport.WEBSOCKET) }.socket("/", SocketOptions { ackTimeout = kotlin.time.Duration.parse("60s") })
            socket.awaitConnect()
            val disconnects = java.util.concurrent.CopyOnWriteArrayList<String>()
            socket.onDisconnect { reason, details -> disconnects += "${reason.wireValue} $details" }
            val payload = ByteArray(1024 * 1024) { it.toByte() }
            val replies =
                kotlinx.coroutines.coroutineScope {
                    List(40) { async { socket.emitWithAck("parity-binary", payload)[0].bytes!!.size } }.awaitAll()
                }
            assertEquals(List(40) { payload.size }, replies)
            assertEquals(emptyList<String>(), disconnects)
            socket.disconnect()
        }

    // A single WebSocket message above OkHttp's fixed 16 MiB queue cannot be sent at all. Instead
    // of OkHttp's silent close, the disconnect names the limit, and the connection recovers.
    @Test
    fun aWebSocketMessageAboveOkHttpsLimitFailsWithAClearReasonAndTheSocketRecovers() =
        e2e("server.js", mapOf("MAX_HTTP_BUFFER_SIZE" to "100000000")) {
            val socket =
                manager {
                    transports = listOf(Transport.WEBSOCKET)
                    reconnectionDelay = kotlin.time.Duration.parse("100ms")
                }.socket("/")
            socket.awaitConnect()
            val disconnects = java.util.concurrent.CopyOnWriteArrayList<io.github.kaeferfreund.socketio.DisconnectDetails?>()
            socket.onDisconnect { _, details -> disconnects += details }
            socket.emit("parity-binary", ByteArray(17 * 1024 * 1024))
            await<Unit> { done -> socket.onConnect { done.complete(Unit) } }
            val cause = disconnects.single()!!.error!!.cause!!
            assertEquals(true, cause.message!!.contains("exceeds the WebSocket client's limit of 16777216 bytes"), cause.message)
            assertEquals(ByteArray(3).size, socket.emitWithAck("parity-binary", ByteArray(3))[0].bytes!!.size)
            socket.disconnect()
        }
}
