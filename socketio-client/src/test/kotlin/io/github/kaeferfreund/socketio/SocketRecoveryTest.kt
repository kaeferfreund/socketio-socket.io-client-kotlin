package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** Connection state recovery: pid and offset bookkeeping, replay and the recovered flag. */
class SocketRecoveryTest {
    @Test
    fun recoversTheSessionAndReplaysMissedEvents() =
        runTest {
            val h = clientHarness(recovery = true)
            val received = ArrayList<String>()
            val socket = h.manager { reconnectionDelay = 1.seconds }.socket { on("news") { received += it[0]!!.string!! } }
            h.settle()
            val id = socket.id
            assertFalse(socket.recovered)
            h.server.namespace("/").emit("news", "one")
            h.settle()
            h.server.engine.sessions.values.single().kill()
            h.settle()
            h.server.namespace("/").emit("news", "missed")
            advanceTimeBy(2.seconds)
            assertTrue(socket.connected)
            assertTrue(socket.recovered)
            assertEquals(id, socket.id)
            assertEquals(listOf("one", "missed"), received)
            val connects = h.server.received.filter { it.type == SocketIOPacketType.CONNECT }
            assertEquals(null, connects[0].data)
            val recovery = connects[1].data!!.obj!!
            assertEquals("pid0", recovery["pid"]!!.string)
            assertEquals("0", recovery["offset"]!!.string)
            h.close()
        }

    @Test
    fun theOffsetIsNotTakenFromEventsWithAcknowledgements() =
        runTest {
            val h = clientHarness(recovery = true)
            val socket = h.manager { reconnectionDelay = 1.seconds }.socket { on("q") { it.ack?.send() } }
            h.settle()
            h.server.namespace("/").clients.single().emitWithAck("q", "not-an-offset") {}
            h.settle()
            h.server.engine.sessions.values.single().kill()
            advanceTimeBy(2.seconds)
            val recovery = h.server.received.filter { it.type == SocketIOPacketType.CONNECT }[1].data!!.obj!!
            assertEquals(io.github.kaeferfreund.socketio.parser.SocketIOValue.Null, recovery["offset"])
            assertTrue(socket.connected)
            h.close()
        }

    @Test
    fun userAuthIsMergedAfterPidAndOffset() =
        runTest {
            val h = clientHarness(recovery = true)
            val socket = h.manager { reconnectionDelay = 1.seconds }.socket(options = SocketOptions { auth = mapOf("token" to "t") })
            h.settle()
            h.server.engine.sessions.values.single().kill()
            advanceTimeBy(2.seconds)
            val keys = h.server.received.filter { it.type == SocketIOPacketType.CONNECT }[1].data!!.obj!!.keys.toList()
            assertEquals(listOf("pid", "offset", "token"), keys)
            assertTrue(socket.connected)
            h.close()
        }

    @Test
    fun aServerWithoutRecoveryNeverReportsRecovered() =
        runTest {
            val h = clientHarness(recovery = false)
            val socket = h.manager { reconnectionDelay = 1.seconds }.socket()
            h.settle()
            val id = socket.id
            h.server.engine.sessions.values.single().kill()
            advanceTimeBy(2.seconds)
            assertTrue(socket.connected)
            assertFalse(socket.recovered)
            assertTrue(id != socket.id)
            h.close()
        }
}
