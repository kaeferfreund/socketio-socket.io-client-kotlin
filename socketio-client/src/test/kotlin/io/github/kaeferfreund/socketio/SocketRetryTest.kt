package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** The ordered retry queue (`retries`), on virtual time. */
class SocketRetryTest {
    // JS-057 with the original 10 ms acknowledgement timeout.
    @Test
    fun doesNotDrainTheQueueWhileDisconnected() =
        runClientTest {
            val h = clientHarness()
            val socket =
                h.manager { autoConnect = false }.socket(
                    options =
                    SocketOptions {
                        retries = 3
                        ackTimeout = 10.milliseconds
                    },
                )
            var result: Result<*>? = null
            socket.emit("echo", 1) { result = it }
            advanceTimeBy(100.milliseconds)
            assertNull(result)
            assertEquals(1, socket.pendingEmits.value)
            socket.connect()
            h.settle()
            assertTrue(result!!.isSuccess)
            assertEquals(0, socket.pendingEmits.value)
            h.close()
        }

    @Test
    fun retriesWithAFreshAckIdAndGivesUpAfterTheConfiguredRetries() =
        runClientTest {
            val h = clientHarness { namespace("/").onConnection { client -> client.on("never") { _, _ -> } } }
            val socket =
                h.manager().socket(
                    options =
                    SocketOptions {
                        retries = 2
                        ackTimeout = 50.milliseconds
                    },
                )
            h.settle()
            var result: Result<*>? = null
            socket.emit("never") { result = it }
            advanceTimeBy(200.milliseconds)
            assertTrue(result!!.exceptionOrNull() is AckTimeoutException)
            val ids = h.server.received.filter { it.type == SocketIOPacketType.EVENT }.map { it.id }
            assertEquals(listOf(0L, 1L, 2L), ids)
            h.close()
        }

    @Test
    fun theQueueReadsAcknowledgementsErrFirstEvenWithoutATimeout() =
        runClientTest {
            // JavaScript would treat the first acknowledgement argument ("x") as an error here
            // and resend the packet; the Kotlin queue always uses err-first callbacks.
            val h = clientHarness()
            val socket = h.manager().socket(options = SocketOptions { retries = 3 })
            h.settle()
            var result: Result<List<io.github.kaeferfreund.socketio.parser.SocketIOValue>>? = null
            socket.emit("echo", "x") { result = it }
            h.settle()
            assertEquals("x", result!!.getOrThrow()[0].string)
            assertEquals(1, h.server.received.count { it.type == SocketIOPacketType.EVENT })
            h.close()
        }

    @Test
    fun volatileEmitsBypassTheQueue() =
        runClientTest {
            val h = clientHarness()
            val socket = h.manager().socket(options = SocketOptions { retries = 3 })
            h.settle()
            socket.volatile.emit("hi")
            h.settle()
            assertEquals(0, socket.pendingEmits.value)
            h.close()
        }
}
