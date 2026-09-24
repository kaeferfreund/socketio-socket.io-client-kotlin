package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** Regressions for review findings: cancellation, recovery reset, isolation and misconfiguration. */
class SocketRobustnessTest {
    @Test
    fun cancellingEmitWithAckRemovesTheEmitFromTheRetryQueue() =
        runClientTest {
            val h =
                clientHarness {
                    namespace("/").onConnection { c ->
                        c.on("never") { _, _ -> }
                        c.on("echo") { args, ack -> ack?.invoke(args) }
                    }
                }
            val socket = h.manager().socket(options = SocketOptions { retries = 3 })
            h.settle()
            val first = async { socket.emitWithAck("never", 1) }
            h.settle()
            var second: String? = null
            socket.emit("echo", "next") { second = it.getOrThrow()[0].string }
            h.settle()
            assertEquals(2, socket.pendingEmits.value)
            first.cancel()
            h.settle()
            // The cancelled head left the queue, so the next emit was sent and acknowledged.
            assertEquals("next", second)
            assertEquals(0, socket.pendingEmits.value)
            assertEquals(0, socket.pendingAcknowledgements.value)
            assertEquals(1, h.server.received.count { it.type == SocketIOPacketType.EVENT && it.data?.array?.get(0)?.string == "never" })
        }

    @Test
    fun clearRecoveryStateStartsAFreshSession() =
        runClientTest {
            val h = clientHarness(recovery = true)
            val socket = h.manager { reconnectionDelay = 1.seconds }.socket()
            h.settle()
            val id = socket.id
            socket.clearRecoveryState()
            h.server.engine.sessions.values.last().kill()
            advanceTimeBy(2.seconds)
            assertTrue(socket.connected)
            assertTrue(!socket.recovered)
            assertTrue(id != socket.id)
            val connect = h.server.received.filter { it.type == SocketIOPacketType.CONNECT }.last()
            assertEquals(null, connect.data)
        }

    @Test
    fun aMissingHttpStackIsAConnectErrorNotACrash() =
        runClientTest {
            val h = clientHarness()
            val errors = ArrayList<Throwable>()
            val manager = h.manager { clients = EngineClients(null, null) }
            manager.socket { onConnectError { errors += it } }
            h.settle()
            assertTrue(errors.single().message!!.contains("EngineHttpClient"), "${errors.single()}")
        }

    @Test
    fun aThrowingInterceptorIsReportedAndDoesNotStopTheEmit() =
        runClientTest {
            val h = clientHarness()
            val reported = ArrayList<Throwable>()
            val interceptor =
                object : OutgoingInterceptor {
                    override fun onSent(event: OutgoingEvent): Unit = error("interceptor bug")
                }
            val socket = h.manager { listenerErrorHandler = { reported += it } }.socket(options = SocketOptions { outgoingInterceptor = interceptor })
            h.settle()
            var acknowledged = false
            socket.emit("echo", 1) { acknowledged = true }
            h.settle()
            assertTrue(acknowledged)
            assertEquals("interceptor bug", reported.single().message)
        }

    @Test
    fun theSetupBlockRunsOnlyWhenTheSocketIsCreated() =
        runClientTest {
            val h = clientHarness()
            val manager = h.manager()
            var runs = 0
            val first = manager.socket("/") { runs++ }
            val second = manager.socket("/") { runs++ }
            h.settle()
            assertTrue(first === second)
            assertEquals(1, runs)
        }

    @Test
    fun aBoundedEventFlowRejectsInvalidCapacities() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { SocketManagerOptions { eventFlowCapacity = 0 } }
        assertEquals(16, SocketManagerOptions { eventFlowCapacity = 16 }.eventFlowCapacity)
    }
}
