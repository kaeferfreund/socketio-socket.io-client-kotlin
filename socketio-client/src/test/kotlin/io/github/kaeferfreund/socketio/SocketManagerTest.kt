package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.EngineSocket
import io.github.kaeferfreund.socketio.engineio.EngineState
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Manager behaviour on virtual time against the in-memory server. */
class SocketManagerTest {
    // JS-018 with exact values: 100 ms × 2^n with ±20 % jitter from a seeded random.
    @Test
    fun reconnectionDelaysFollowTheBackoffExactly() =
        runTest {
            val h = clientHarness()
            h.server.engine.allowRequest = { 503 }
            val times = ArrayList<Long>()
            val failedAt = ArrayList<Long>()
            val manager =
                h.manager(setup = {
                    on<ManagerEvent.Error> { failedAt += testScheduler.currentTime }
                    on<ManagerEvent.ReconnectAttempt> { times += testScheduler.currentTime }
                }) {
                    reconnectionAttempts = 3
                    reconnectionDelay = 100.milliseconds
                    reconnectionDelayMax = 10.seconds
                    randomizationFactor = 0.2
                }
            manager.socket("/")
            advanceTimeBy(5.seconds)
            val delays = times.indices.map { times[it] - failedAt[it] }
            assertEquals(3, delays.size)
            val expected = Backoff(100.milliseconds, 10.seconds, 0.2, kotlin.random.Random(42)).let { b -> List(3) { b.duration().inWholeMilliseconds } }
            assertEquals(expected, delays)
            assertTrue(delays[0] in 80..120 && delays[1] in 160..240 && delays[2] in 320..480, "$delays")
            h.close()
        }

    @Test
    fun theBackoffIsCappedAtTheMaximumDelay() {
        val backoff = Backoff(1.seconds, 5.seconds, 0.0, kotlin.random.Random(1))
        assertEquals(listOf(1000L, 2000L, 4000L, 5000L, 5000L), List(5) { backoff.duration().inWholeMilliseconds })
        backoff.reset()
        assertEquals(1000L, backoff.duration().inWholeMilliseconds)
    }

    // JS-034: timers run on the configured dispatcher, so a test clock drives the reconnect delay.
    @Test
    fun reconnectTimersFollowTheConfiguredClock() =
        runTest {
            val h = clientHarness()
            var reconnected = false
            val manager =
                h.manager(setup = { on<ManagerEvent.ReconnectAttempt> { reconnected = true } }) {
                    timeout = 500.milliseconds
                    reconnectionDelayMax = 100.milliseconds
                    randomizationFactor = 0.0
                }
            val socket = manager.socket("/foo")
            h.server.namespace("/foo")
            h.settle()
            h.server.engine.sessions.values.single().kill()
            h.settle()
            advanceTimeBy(99.milliseconds)
            assertFalse(reconnected)
            advanceTimeBy(2.milliseconds)
            assertTrue(reconnected)
            socket.disconnect()
            h.close()
        }

    // JS-028
    @Test
    fun emitsConnectErrorForASocketIoV2Server() =
        runTest {
            val h = clientHarness()
            // A v2 server answers CONNECT without a session id.
            h.server.engine.onConnection = { session ->
                session.onMessage = { session.send("0") }
            }
            val errors = ArrayList<Throwable>()
            val manager = h.manager()
            manager.socket("/") { onConnectError { errors += it } }
            h.settle()
            assertTrue(errors.single().message!!.startsWith("It seems you are trying to reach a Socket.IO server in v2.x"))
            h.close()
        }

    // JS-049: the server sends an undecodable packet.
    @Test
    fun closesTheEngineUponADecodingException() =
        runTest {
            val h = clientHarness()
            val engines = ArrayList<EngineSocket>()
            var reasons = ArrayList<DisconnectReason>()
            var reconnected = false
            val manager =
                h.manager(setup = {
                    on<ManagerEvent.Open> { engines += engine!! }
                    on<ManagerEvent.Close> { reasons += it.reason }
                    on<ManagerEvent.Reconnect> { reconnected = true }
                }) { reconnectionDelay = 50.milliseconds }
            manager.socket("/")
            h.settle()
            h.server.engine.sessions.values.single().send("bad")
            h.settle()
            advanceTimeBy(100.milliseconds)
            assertEquals(listOf(DisconnectReason.PARSE_ERROR), reasons)
            assertTrue(reconnected)
            assertEquals(2, engines.size)
            assertNotSame(engines[0], engines[1])
            assertEquals(EngineState.CLOSED, engines[0].readyState)
            h.close()
        }

    @Test
    fun emitsOneCloseAndDisconnectOnlyAfterConnect() =
        runTest {
            val h = clientHarness()
            val closes = ArrayList<DisconnectReason>()
            val manager = h.manager(setup = { on<ManagerEvent.Close> { closes += it.reason } }) { reconnectionDelay = 1.seconds }
            val events = ArrayList<String>()
            val socket =
                manager.socket("/") {
                    onConnect { events += "connect" }
                    onDisconnect { reason, _ -> events += "disconnect:${reason.wireValue}" }
                }
            h.settle()
            h.server.engine.sessions.values.single().kill()
            h.settle()
            // Disconnecting while waiting to reconnect: JavaScript would emit close and disconnect again.
            socket.disconnect()
            manager.disconnect()
            h.settle()
            assertEquals(listOf(DisconnectReason.TRANSPORT_ERROR), closes)
            assertEquals(listOf("connect", "disconnect:transport error"), events)
            h.close()
        }

    @Test
    fun anUnavailableNetworkDefersReconnectionUntilItReturns() =
        runTest {
            val h = clientHarness()
            val attempts = ArrayList<Long>()
            val manager =
                h.manager(setup = { on<ManagerEvent.ReconnectAttempt> { attempts += testScheduler.currentTime } }) {
                    reconnectionDelay = 1.seconds
                    randomizationFactor = 0.0
                }
            val socket = manager.socket("/")
            h.settle()
            manager.setNetworkAvailable(false)
            h.server.engine.sessions.values.single().kill()
            h.settle()
            advanceTimeBy(10.seconds)
            assertTrue(attempts.isEmpty())
            val back = testScheduler.currentTime
            manager.setNetworkAvailable(true)
            h.settle()
            assertEquals(listOf(back), attempts)
            assertTrue(socket.connected)
            h.close()
        }

    @Test
    fun reconnectNowSkipsTheRemainingBackoff() =
        runTest {
            val h = clientHarness()
            val manager = h.manager { reconnectionDelay = 5.seconds }
            val socket = manager.socket("/")
            h.settle()
            h.server.engine.sessions.values.single().kill()
            h.settle()
            assertFalse(socket.connected)
            manager.reconnectNow()
            h.settle()
            assertTrue(socket.connected)
            h.close()
        }

    @Test
    fun aConnectTimeoutOfZeroFailsEveryAttemptImmediately() =
        runTest {
            val h = clientHarness()
            val errors = ArrayList<String?>()
            val manager =
                h.manager(setup = { on<ManagerEvent.Error> { errors += it.error.message } }) {
                    timeout = Duration.ZERO
                    reconnection = false
                }
            manager.socket("/")
            h.settle()
            assertEquals(listOf("timeout"), errors)
            h.close()
        }

    @Test
    fun listenerExceptionsNeverReachTheProtocol() =
        runTest {
            val h = clientHarness()
            val reported = ArrayList<Throwable>()
            val manager = h.manager { listenerErrorHandler = { reported += it } }
            val socket =
                manager.socket("/") {
                    onConnect { error("listener bug") }
                    on("hi") { error("listener bug 2") }
                }
            h.settle()
            socket.emit("hi")
            h.settle()
            assertTrue(socket.connected)
            assertEquals(listOf("listener bug", "listener bug 2"), reported.map { it.message })
            h.close()
        }

    @Test
    fun socketsShareOneConnectionAndTheLastDisconnectClosesIt() =
        runTest {
            val h = clientHarness { namespace("/foo") }
            val manager = h.manager()
            val a = manager.socket("/")
            val b = manager.socket("/foo")
            h.settle()
            assertTrue(a.connected && b.connected)
            assertEquals(1, h.server.engine.sessions.size)
            a.disconnect()
            h.settle()
            assertTrue(h.server.engine.sessions.values.single().isOpen)
            b.disconnect()
            h.settle()
            assertFalse(h.server.engine.sessions.values.single().isOpen)
            h.close()
        }

    @Test
    fun publishesTheTransportName() =
        runTest {
            val h = clientHarness()
            val manager = h.manager()
            manager.socket("/")
            h.settle()
            assertEquals("websocket", manager.transportName.value)
            manager.disconnect()
            h.settle()
            assertEquals(null, manager.transportName.value)
            h.close()
        }

    @Test
    fun forwardsDecodedPacketsAsManagerEvents() =
        runTest {
            val h = clientHarness()
            val packets = ArrayList<SocketIOPacket>()
            val manager = h.manager(setup = { on<ManagerEvent.Packet> { packets += it.packet } })
            val socket = manager.socket("/")
            h.settle()
            socket.emit("hi")
            h.settle()
            assertEquals(listOf(SocketIOPacketType.CONNECT, SocketIOPacketType.EVENT), packets.map { it.type })
            assertEquals(SocketIOValue.arrayOf("hi"), packets[1].data)
            h.close()
        }
}

class SocketManagerOpenOrderTest {
    // An open listener that closes the connection synchronously must not leave the
    // manager "open" with a dead engine (JavaScript subscribes after emitting "open").
    @Test
    fun anOpenListenerThatClosesTheConnectionIsNoticed() =
        runTest {
            val h = clientHarness()
            var lost = false
            val manager =
                h.manager(setup = {
                    on<ManagerEvent.Open> {
                        if (!lost) {
                            lost = true
                            onNetworkLost()
                        }
                    }
                }) { reconnectionDelay = 100.milliseconds }
            val socket = manager.socket("/")
            h.settle()
            advanceTimeBy(1.seconds)
            assertTrue(lost)
            assertTrue(socket.connected)
            assertEquals(2, h.server.engine.sessions.size)
            h.close()
        }
}
