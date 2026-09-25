package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpClient
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.engineio.SocketLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The JavaScript `Manager` API beyond connecting: runtime setters, open callbacks, pausing, tracing and callback threads. */
class SocketManagerApiTest {
    @Test
    fun runtimeReconnectionSettersApplyToTheNextAttempts() =
        runClientTest {
            val h = clientHarness()
            h.server.engine.allowRequest = { 503 }
            val attempts = ArrayList<Long>()
            val errors = ArrayList<Long>()
            var failed = false
            val manager =
                h.manager(setup = {
                    on<ManagerEvent.Error> { errors += testScheduler.currentTime }
                    on<ManagerEvent.ReconnectAttempt> { attempts += testScheduler.currentTime }
                    on<ManagerEvent.ReconnectFailed> { failed = true }
                }) {
                    reconnectionDelay = 100.milliseconds
                    reconnectionDelayMax = 100.milliseconds
                    randomizationFactor = 0.0
                }
            manager.reconnectionDelay = 1.seconds
            manager.reconnectionDelayMax = 3.seconds
            manager.randomizationFactor = 0.0
            manager.reconnectionAttempts = 3
            assertEquals(1.seconds, manager.reconnectionDelay)
            assertEquals(3.seconds, manager.reconnectionDelayMax)
            assertEquals(0.0, manager.randomizationFactor)
            assertEquals(3, manager.reconnectionAttempts)
            assertTrue(manager.reconnection)
            manager.socket("/")
            advanceTimeBy(30.seconds)
            // 1 s, 2 s, then capped at 3 s; three attempts and the loop gives up.
            assertEquals(listOf(1000L, 2000L, 3000L), attempts.indices.map { attempts[it] - errors[it] })
            assertTrue(failed)
            assertThrows<IllegalArgumentException> { manager.reconnectionAttempts = -1 }
            assertThrows<IllegalArgumentException> { manager.randomizationFactor = 1.5 }
            h.close()
        }

    @Test
    fun switchingReconnectionOffStopsAPendingAttempt() =
        runClientTest {
            val h = clientHarness()
            h.server.engine.allowRequest = { 503 }
            var attempts = 0
            val manager =
                h.manager(setup = { on<ManagerEvent.ReconnectAttempt> { attempts++ } }) {
                    reconnectionDelay = 1.seconds
                    randomizationFactor = 0.0
                }
            manager.socket("/")
            h.settle()
            manager.reconnection = false
            assertFalse(manager.reconnection)
            advanceTimeBy(60.seconds)
            assertEquals(0, attempts)
            h.close()
        }

    @Test
    fun theTimeoutEndsAnAttemptWhoseServerNeverCompletesTheHandshake() =
        runClientTest {
            val h = clientHarness()
            val errors = ArrayList<Pair<Long, String?>>()
            val manager =
                SocketManager(
                    "http://fake.test",
                    SocketManagerOptions {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        timeSource = testScheduler.timeSource
                        // Answers every poll with a message instead of the open packet: the engine waits forever.
                        clients = EngineClients(SilentServer(this@runClientTest), null)
                        reconnection = false
                        autoConnect = false
                    },
                ) { on<ManagerEvent.Error> { errors += testScheduler.currentTime to it.error.message } }
            h.managers += manager
            manager.timeout = 2.seconds
            assertEquals(2.seconds, manager.timeout)
            manager.connect()
            advanceTimeBy(1999.milliseconds)
            assertTrue(errors.isEmpty())
            advanceTimeBy(2.milliseconds)
            assertEquals(listOf(2000L to "timeout"), errors)
            h.close()
        }

    @Test
    fun openWithACallbackReportsTheOutcomeOnceAndDoesNotReconnect() =
        runClientTest {
            val h = clientHarness()
            val outcomes = ArrayList<Throwable?>()
            var attempts = 0
            val manager = h.manager(setup = { on<ManagerEvent.ReconnectAttempt> { attempts++ } }) { autoConnect = false }
            manager.open { outcomes += it }
            h.settle()
            assertEquals(listOf<Throwable?>(null), outcomes)
            manager.disconnect()
            h.settle()
            h.server.engine.allowRequest = { 503 }
            manager.open { outcomes += it }
            advanceTimeBy(30.seconds)
            assertEquals(2, outcomes.size)
            assertTrue(outcomes[1] != null)
            assertEquals(0, attempts)
            h.close()
        }

    @Test
    fun pauseClosesTheConnectionAndResumeReconnectsTheActiveSockets() =
        runClientTest {
            val h = clientHarness()
            val reasons = ArrayList<DisconnectReason>()
            val manager = h.manager()
            val socket = manager.socket("/") { onDisconnect { reason, _ -> reasons += reason } }
            h.settle()
            assertTrue(socket.connected)
            manager.pause()
            h.settle()
            assertTrue(manager.isPaused)
            assertEquals(listOf(DisconnectReason.FORCED_CLOSE), reasons)
            assertFalse(socket.connected)
            assertTrue(socket.active)
            // Nothing reconnects while paused, not even an explicit open.
            manager.open()
            advanceTimeBy(60.seconds)
            assertFalse(socket.connected)
            assertEquals(1, h.server.engine.sessions.size)
            manager.resume()
            h.settle()
            assertFalse(manager.isPaused)
            assertTrue(socket.connected)
            assertEquals(2, h.server.engine.sessions.size)
            // Resuming again is harmless.
            manager.resume()
            h.settle()
            assertEquals(2, h.server.engine.sessions.size)
            h.close()
        }

    @Test
    fun pauseDuringReconnectionStopsTheLoop() =
        runClientTest {
            val h = clientHarness()
            var attempts = 0
            val manager =
                h.manager(setup = { on<ManagerEvent.ReconnectAttempt> { attempts++ } }) {
                    reconnectionDelay = 1.seconds
                    randomizationFactor = 0.0
                }
            val socket = manager.socket("/")
            h.settle()
            h.server.engine.sessions.values.single().kill()
            h.settle()
            manager.pause()
            advanceTimeBy(60.seconds)
            assertEquals(0, attempts)
            manager.resume()
            h.settle()
            assertTrue(socket.connected)
            h.close()
        }

    @Test
    fun resumeWithoutActiveSocketsStaysClosed() =
        runClientTest {
            val h = clientHarness()
            val manager = h.manager()
            val socket = manager.socket("/")
            h.settle()
            manager.pause()
            h.settle()
            socket.disconnect()
            manager.resume()
            h.settle()
            assertFalse(socket.connected)
            assertEquals(1, h.server.engine.sessions.size)
            h.close()
        }

    @Test
    fun onNetworkLostClosesWithTransportCloseAndReconnects() =
        runClientTest {
            val h = clientHarness()
            val reasons = ArrayList<DisconnectReason>()
            val manager =
                h.manager {
                    reconnectionDelay = 1.seconds
                    randomizationFactor = 0.0
                }
            val socket = manager.socket("/") { onDisconnect { reason, _ -> reasons += reason } }
            h.settle()
            manager.onNetworkLost()
            h.settle()
            assertEquals(listOf(DisconnectReason.TRANSPORT_CLOSE), reasons)
            assertTrue(DisconnectReason.TRANSPORT_CLOSE.reconnectsAutomatically)
            advanceTimeBy(1.seconds + 1.milliseconds)
            assertTrue(socket.connected)
            h.close()
        }

    @Test
    fun theTracerSeesBalancedConnectUpgradeAndAcknowledgementSections() =
        runClientTest {
            val h = clientHarness()
            val open = HashMap<Pair<String, Int>, Int>()
            val names = LinkedHashSet<String>()
            val tracer =
                object : SocketTracer {
                    override fun beginAsyncSection(
                        name: String,
                        cookie: Int,
                    ) {
                        names += name
                        open.merge(name to cookie, 1, Int::plus)
                    }

                    override fun endAsyncSection(
                        name: String,
                        cookie: Int,
                    ) {
                        open.merge(name to cookie, -1, Int::plus)
                    }
                }
            val manager = h.manager { this.tracer = tracer }
            val socket = manager.socket("/")
            h.settle()
            val acks = ArrayList<Result<List<*>>>()
            socket.emit("echo", 1) { acks += it }
            h.settle()
            assertEquals(1, acks.size)
            assertEquals(listOf("socket.io connect", "socket.io upgrade", "socket.io ack echo"), names.toList())
            assertTrue(open.values.all { it == 0 }, "unbalanced sections: $open")
            h.close()
        }

    @Test
    fun listenerCallbacksRunOnTheCallbackDispatcher() =
        runClientTest {
            val h = clientHarness()
            val callbacks = CountingDispatcher(StandardTestDispatcher(testScheduler))
            val events = ArrayList<String>()
            val manager = h.manager { callbackDispatcher = callbacks }
            val socket =
                manager.socket("/") {
                    onConnect { events += "connect" }
                    on("hi") { events += "hi" }
                }
            h.settle()
            socket.emit("hi")
            h.settle()
            assertEquals(listOf("connect", "hi"), events)
            assertTrue(callbacks.dispatches >= 2)
            h.close()
        }

    @Test
    fun aListenerExceptionWithoutHandlerIsLoggedAndTheConnectionSurvives() =
        runClientTest {
            val h = clientHarness()
            val logged = ArrayList<String>()
            val logger =
                object : SocketLogger {
                    override fun isLoggable(level: LogLevel) = level == LogLevel.ERROR

                    override fun log(
                        level: LogLevel,
                        tag: String,
                        message: String,
                        error: Throwable?,
                    ) {
                        logged += "$tag: $message: ${error?.message}"
                    }
                }
            val manager = h.manager { this.logger = logger }
            val socket = manager.socket("/") { onConnect { error("listener bug") } }
            h.settle()
            assertTrue(socket.connected)
            assertEquals(listOf("listener: a listener threw: listener bug"), logged)
            h.close()
        }

    @Test
    fun withoutLoggerAListenerExceptionGoesToStandardError() =
        runClientTest {
            val h = clientHarness()
            val captured = ByteArrayOutputStream()
            val original = System.err
            System.setErr(PrintStream(captured, true))
            try {
                val manager = h.manager()
                val socket = manager.socket("/") { onConnect { error("unhandled listener bug") } }
                h.settle()
                assertTrue(socket.connected)
            } finally {
                System.setErr(original)
            }
            assertTrue(captured.toString().contains("socket.io listener threw: java.lang.IllegalStateException: unhandled listener bug"), captured.toString())
            h.close()
        }

    @Test
    fun aBoundedEventFlowKeepsTheNewestEvents() =
        runClientTest {
            val h = clientHarness()
            val manager = h.manager { eventFlowCapacity = 2 }
            val socket = manager.socket("/")
            h.settle()
            assertTrue(socket.connected)
            assertEquals(2, manager.options.eventFlowCapacity)
            h.close()
        }

    @Test
    fun integrationsStoreTypedValuesInTheOptions() {
        val key = SocketManagerOptions.Key<String>("demo")
        val other = SocketManagerOptions.Key<Int>("other")
        val options = SocketManagerOptions { set(key, "value") }
        assertEquals("value", options[key])
        assertNull(options[other])
        assertEquals("Key(demo)", key.toString())
        val builder = options.newBuilder()
        assertEquals("value", builder.get(key))
        assertEquals("value", builder.build()[key])
    }
}

/** Answers every request with a Socket.IO message instead of the Engine.IO open packet. */
private class SilentServer(
    private val scope: kotlinx.coroutines.test.TestScope,
) : EngineHttpClient {
    override fun execute(
        request: EngineHttpRequest,
        callback: EngineHttpCallback,
    ): Cancellable {
        scope.backgroundScope.launch { callback.onResponse(EngineHttpResponse(200, "40")) }
        return Cancellable.NONE
    }
}

private class CountingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    var dispatches = 0

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatches++
        delegate.dispatch(context, block)
    }
}
