package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.testing.FakeSocketIOServer
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The published in-memory servers behave like a real server where applications
 * test against them: packets written before a close arrive, and refusals look
 * like the ones socket.io sends.
 */
class FakeServerFidelityTest {
    @Test
    fun aServerSideDisconnectWithCloseArrivesOverWebSocket() = serverSideDisconnectWithClose(upgrades = listOf("websocket"), transport = "websocket")

    @Test
    fun aServerSideDisconnectWithCloseArrivesOverPolling() = serverSideDisconnectWithClose(upgrades = emptyList(), transport = "polling")

    /** engine.io answers the next poll with the close packet when none is waiting. */
    @Test
    fun aPollingCloseWithoutAWaitingPollArrivesWithTheNextPoll() =
        runClientTest {
            val h = clientHarness(upgrades = emptyList())
            val manager = h.manager { reconnection = false }
            val reasons = ArrayList<DisconnectReason>()
            manager.on<ManagerEvent.Close> { reasons += it.reason }
            manager.socket("/")
            h.settle()
            val session = h.server.engine.sessions.values.single()
            // The message answers the waiting poll; the close finds none.
            session.send("2[\"news\"]")
            session.close()
            h.settle()
            assertEquals(listOf(DisconnectReason.TRANSPORT_CLOSE), reasons)
            assertFalse(session.isOpen)
            h.close()
        }

    private fun serverSideDisconnectWithClose(
        upgrades: List<String>,
        transport: String,
    ) = runClientTest {
        val h = clientHarness(upgrades = upgrades)
        val manager = h.manager()
        val reasons = ArrayList<DisconnectReason>()
        val socket = manager.socket("/") { onDisconnect { reason, _ -> reasons += reason } }
        h.settle()
        assertEquals(transport, manager.transportName.value)
        // socket.disconnect(true) on a real server: DISCONNECT for the namespace, then the close.
        h.server.namespace("/").clients.single().disconnect(closeConnection = true)
        h.settle()
        advanceTimeBy(30.seconds)
        h.settle()
        assertEquals(listOf(DisconnectReason.IO_SERVER_DISCONNECT), reasons)
        assertFalse(socket.active)
        assertEquals(1, h.server.engine.sessions.size, "the client reconnected")
        h.close()
    }

    @Test
    fun aRefusalWithoutDataCarriesNoData() =
        runClientTest {
            val h = clientHarness { namespace("/").use { FakeSocketIOServer.Refusal("not allowed") } }
            val errors = ArrayList<Throwable>()
            h.manager().socket("/") { onConnectError { errors += it } }
            h.settle()
            val error = errors.single() as SocketConnectException
            assertEquals("not allowed", error.message)
            assertNull(error.data)
            assertTrue(error.isServerRefusal)
            h.close()
        }
}
