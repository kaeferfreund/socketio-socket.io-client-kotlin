package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.AckTimeoutException
import io.github.kaeferfreund.socketio.ManagerEvent
import io.github.kaeferfreund.socketio.SocketConnectException
import io.github.kaeferfreund.socketio.SocketDisconnectedException
import io.github.kaeferfreund.socketio.SocketOptions
import io.github.kaeferfreund.socketio.Transport
import io.github.kaeferfreund.socketio.engineio.EnginePacketObserver
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Ports of `packages/socket.io-client/test/socket.ts` against the ported upstream test server. */
class SocketE2ETest {
    // JS-059
    @Test
    fun hasAnIdEqualToTheServerSideIdOnTheDefaultNamespace() =
        e2e {
            val socket = io()
            val serverId = socket.emitWithAck("getId")[0].string
            assertNotNull(socket.id)
            assertEquals(serverId, socket.id)
            socket.disconnect()
        }

    // JS-060
    @Test
    fun hasAnIdEqualToTheServerSideIdOnACustomNamespace() =
        e2e {
            val socket = io("/foo")
            val serverId = socket.emitWithAck("getId")[0].string
            assertNotNull(socket.id)
            assertEquals(serverId, socket.id)
            socket.disconnect()
        }

    // JS-061
    @Test
    fun clearsTheIdUponDisconnection() =
        e2e {
            val socket = io()
            socket.awaitConnect()
            val idAtDisconnect = await<String?> { d -> socket.onDisconnect { _, _ -> d.complete(socket.id) }.also { socket.disconnect() } }
            assertNull(idAtDisconnect)
        }

    // JS-062
    @Test
    fun doesNotFireAnErrorIfForceDisconnectedWhileOpening() =
        e2e {
            val unexpected = Unexpected()
            val socket = io(setup = { manager.on<ManagerEvent.Error> { unexpected.fail("error ${it.error}") } }) { timeout = 100.milliseconds }
            socket.disconnect()
            delay(300.milliseconds)
            unexpected.check()
        }

    // JS-063
    @Test
    fun firesConnectErrorWhenTheConnectionCannotBeEstablished() =
        e2e {
            val error = CompletableDeferred<Throwable>()
            val socket =
                io.github.kaeferfreund.socketio.SocketIO.io(
                    "http://localhost:9823",
                    io.github.kaeferfreund.socketio.SocketManagerOptions {
                        forceNew = true
                        timeout = 100.milliseconds
                    },
                ) { onConnectError { error.complete(it) } }
            managers += socket.manager
            withTimeout(10.seconds) { error.await() }
            socket.close()
        }

    // JS-064
    @Test
    fun firesConnectErrorOnOpenTimeoutWithPolling() =
        e2e {
            val error = CompletableDeferred<Throwable>()
            val socket =
                io(setup = { onConnectError { error.complete(it) } }) {
                    transports = listOf(Transport.POLLING)
                    timeout = Duration.ZERO
                }
            assertEquals("timeout", withTimeout(10.seconds) { error.await() }.message)
            socket.disconnect()
        }

    // JS-065
    @Test
    fun firesConnectErrorOnOpenTimeoutWithWebSocket() =
        e2e {
            val error = CompletableDeferred<Throwable>()
            val socket =
                io(setup = { onConnectError { error.complete(it) } }) {
                    transports = listOf(Transport.WEBSOCKET)
                    timeout = Duration.ZERO
                }
            assertEquals("timeout", withTimeout(10.seconds) { error.await() }.message)
            socket.disconnect()
        }

    // JS-066
    @Test
    fun doesNotFireConnectErrorWhenTheConnectionIsAlreadyEstablished() =
        e2e {
            val unexpected = Unexpected()
            val socket = io(setup = { onConnectError { unexpected.fail("connect_error $it") } })
            socket.awaitConnect()
            killTransport(socket)
            delay(300.milliseconds)
            socket.close()
            unexpected.check()
        }

    // JS-067
    @Test
    fun changesTheIdUponReconnection() =
        e2e {
            val socket = io { reconnectionDelay = 10.milliseconds }
            socket.awaitConnect()
            val id = socket.id
            val idDuringAttempt = CompletableDeferred<String?>()
            val reconnected = CompletableDeferred<Unit>()
            socket.manager.on<ManagerEvent.ReconnectAttempt> { idDuringAttempt.complete(socket.id) }
            socket.manager.on<ManagerEvent.Reconnect> { reconnected.complete(Unit) }
            killTransport(socket)
            assertNull(withTimeout(10.seconds) { idDuringAttempt.await() })
            withTimeout(10.seconds) { reconnected.await() }
            socket.awaitConnect()
            assertNotEquals(id, socket.id)
            socket.disconnect()
        }

    // JS-068
    @Test
    fun enablesCompressionByDefault() =
        e2e {
            val compress = CompletableDeferred<Boolean>()
            val socket =
                io {
                    packetObserver =
                        EnginePacketObserver { outgoing, packet ->
                            if (outgoing &&
                                packet.text?.startsWith("2") == true
                            ) {
                                compress.complete(packet.options.compress)
                            }
                        }
                }
            socket.awaitConnect()
            socket.emit("hi")
            assertTrue(withTimeout(10.seconds) { compress.await() })
            socket.disconnect()
        }

    // JS-069
    @Test
    fun disablesCompression() =
        e2e {
            val compress = CompletableDeferred<Boolean>()
            val socket =
                io {
                    packetObserver =
                        EnginePacketObserver { outgoing, packet ->
                            if (outgoing &&
                                packet.text?.startsWith("2") == true
                            ) {
                                compress.complete(packet.options.compress)
                            }
                        }
                }
            socket.awaitConnect()
            socket.compress(false).emit("hi")
            assertEquals(false, withTimeout(10.seconds) { compress.await() })
            socket.disconnect()
        }

    // JS-070
    @Test
    fun acceptsAQueryObjectOnTheDefaultNamespace() =
        e2e {
            val socket = io("/") { query = mapOf("e" to "f") }
            val handshake = socket.emitWithAck("getHandshake")[0] as SocketIOValue.Object
            assertEquals("f", handshake["query"]!!.obj!!["e"]!!.string)
            socket.disconnect()
        }

    // JS-071
    @Test
    fun acceptsAQueryStringOnTheDefaultNamespace() =
        e2e {
            val socket = io("/?c=d")
            val handshake = socket.emitWithAck("getHandshake")[0] as SocketIOValue.Object
            assertEquals("d", handshake["query"]!!.obj!!["c"]!!.string)
            socket.disconnect()
        }

    // JS-072
    @Test
    fun acceptsAQueryObject() =
        e2e {
            val handshake = CompletableDeferred<SocketIOValue>()
            val socket = io("/abc", setup = { on("handshake") { handshake.complete(it[0]!!) } }) { query = mapOf("a" to "b") }
            assertEquals("b", withTimeout(10.seconds) { handshake.await() }.obj!!["query"]!!.obj!!["a"]!!.string)
            socket.disconnect()
        }

    // JS-073
    @Test
    fun acceptsAQueryString() =
        e2e {
            val handshake = CompletableDeferred<SocketIOValue>()
            val socket = io("/abc?b=c&d=e", setup = { on("handshake") { handshake.complete(it[0]!!) } })
            val query = withTimeout(10.seconds) { handshake.await() }.obj!!["query"]!!.obj!!
            assertEquals("c", query["b"]!!.string)
            assertEquals("e", query["d"]!!.string)
            socket.disconnect()
        }

    // JS-074
    @Test
    fun properlyEncodesTheParameters() =
        e2e {
            val handshake = CompletableDeferred<SocketIOValue>()
            val socket = io("/abc", setup = { on("handshake") { handshake.complete(it[0]!!) } }) { query = mapOf("&a" to "&=?a") }
            assertEquals("&=?a", withTimeout(10.seconds) { handshake.await() }.obj!!["query"]!!.obj!!["&a"]!!.string)
            socket.disconnect()
        }

    // JS-075
    @Test
    fun acceptsAnAuthObject() =
        e2e {
            val handshake = CompletableDeferred<SocketIOValue>()
            val socket =
                io("/abc", SocketOptions { auth = mapOf("a" to "b", "c" to "d") }, setup = { on("handshake") { handshake.complete(it[0]!!) } })
            val value = withTimeout(10.seconds) { handshake.await() }.obj!!
            assertEquals("b", value["auth"]!!.obj!!["a"]!!.string)
            assertEquals("d", value["auth"]!!.obj!!["c"]!!.string)
            assertNull(value["query"]!!.obj!!["a"])
            socket.disconnect()
        }

    // JS-076
    @Test
    fun acceptsAnAuthFunction() =
        e2e {
            val handshake = CompletableDeferred<SocketIOValue>()
            val socket =
                io("/abc", SocketOptions { authProvider = io.github.kaeferfreund.socketio.AuthProvider { mapOf("e" to "f") } }, setup = {
                    on("handshake") { handshake.complete(it[0]!!) }
                })
            val value = withTimeout(10.seconds) { handshake.await() }.obj!!
            assertEquals("f", value["auth"]!!.obj!!["e"]!!.string)
            assertNull(value["query"]!!.obj!!["e"])
            socket.disconnect()
        }

    // JS-077
    @Test
    fun firesAnErrorOnMiddlewareFailureFromACustomNamespace() =
        e2e {
            val error = CompletableDeferred<Throwable>()
            val socket = io("/no", setup = { onConnectError { error.complete(it) } })
            val err = withTimeout(10.seconds) { error.await() }
            assertTrue(err is SocketConnectException)
            assertEquals("Auth failed (custom namespace)", err.message)
            socket.disconnect()
        }

    // JS-078
    @Test
    fun firesConnectErrorWithDataOnMiddlewareFailure() =
        e2e {
            val error = CompletableDeferred<Throwable>()
            val socket = io("/with-data", setup = { onConnectError { error.complete(it) } })
            val err = withTimeout(10.seconds) { error.await() } as SocketConnectException
            assertEquals("Auth failed (with data)", err.message)
            assertEquals(SocketIOValue.of(mapOf("code" to 401, "details" to "Invalid token")), err.data)
            socket.disconnect()
        }

    // JS-079
    @Test
    fun doesNotReconnectAfterAMiddlewareFailure() =
        e2e {
            val count = AtomicInteger()
            lateinit var socket: io.github.kaeferfreund.socketio.Socket
            socket =
                io("/no", setup = {
                    onConnectError {
                        count.incrementAndGet()
                        // force reconnection
                        manager.onNetworkLost()
                    }
                }) { reconnectionDelay = 10.milliseconds }
            delay(300.milliseconds)
            assertEquals(1, count.get())
            socket.disconnect()
        }

    // JS-080
    @Test
    fun properlyDisconnectsThenReconnects() =
        e2e {
            val count = AtomicInteger()
            val socket =
                io("/", setup = {
                    val first = java.util.concurrent.atomic.AtomicBoolean(true)
                    onConnect { if (first.getAndSet(false)) disconnect().connect() }
                    onDisconnect { _, _ -> count.incrementAndGet() }
                }) { transports = listOf(Transport.WEBSOCKET) }
            delay(300.milliseconds)
            assertEquals(1, count.get())
            assertTrue(socket.connected)
            socket.disconnect()
        }

    // JS-081
    @Test
    fun throwsOnReservedEvent() =
        e2e {
            val socket = io("/no")
            val error = assertThrows<IllegalArgumentException> { socket.emit("disconnecting", "goodbye") }
            assertEquals("\"disconnecting\" is a reserved event name", error.message)
            socket.disconnect()
        }

    // JS-082
    @Test
    fun emitsEventsInOrder() =
        e2e {
            val order = CopyOnWriteArrayList<String>()
            val done = CompletableDeferred<Unit>()
            val socket =
                io("/", setup = {
                    onConnect {
                        emit("echo", "second") {
                            order += "second"
                            done.complete(Unit)
                        }
                    }
                }) { autoConnect = false }
            socket.emit("echo", "first") { order += "first" }
            socket.connect()
            withTimeout(10.seconds) { done.await() }
            assertEquals(listOf("first", "second"), order)
            socket.disconnect()
        }

    // JS-083
    @Test
    fun emitsAnEventAndWaitsForTheAcknowledgement() =
        e2e {
            val socket = io()
            assertEquals(123L, socket.emitWithAck("echo", 123)[0].long)
            socket.disconnect()
        }

    // JS-084
    @Test
    fun discardsAVolatilePacketWhenTheSocketIsNotConnected() =
        e2e {
            val unexpected = Unexpected()
            val socket = io { autoConnect = false }
            socket.volatile.emit("getId") { unexpected.fail("volatile acknowledged") }
            val done = CompletableDeferred<Unit>()
            socket.emit("getId") { done.complete(Unit) }
            socket.connect()
            withTimeout(10.seconds) { done.await() }
            socket.disconnect()
            unexpected.check()
        }

    // JS-085: right after CONNECT a polling POST is in flight, so the pipe is not writable.
    @Test
    fun discardsAVolatilePacketWhenThePipeIsNotReady() =
        e2e {
            val unexpected = Unexpected()
            val done = CompletableDeferred<Unit>()
            val socket =
                io(setup = {
                    onConnect {
                        emit("getId") { done.complete(Unit) }
                        volatile.emit("getId") { unexpected.fail("volatile acknowledged") }
                    }
                })
            withTimeout(10.seconds) { done.await() }
            delay(100.milliseconds)
            socket.disconnect()
            unexpected.check()
        }

    // JS-086
    @Test
    fun sendsAVolatilePacketWhenConnectedAndThePipeIsReady() =
        e2e {
            val socket = io()
            val done = CompletableDeferred<Unit>()
            while (!done.isCompleted) {
                socket.volatile.emit("getId") { done.complete(Unit) }
                delay(200.milliseconds)
            }
            socket.disconnect()
        }

    // JS-087
    @Test
    fun onAnyCallsTheListener() =
        e2e {
            val received = CompletableDeferred<Pair<String, SocketIOValue?>>()
            val socket = io("/abc", setup = { onAny { name, args -> received.complete(name to args.firstOrNull()) } })
            val (name, arg1) = withTimeout(10.seconds) { received.await() }
            assertEquals("handshake", name)
            assertTrue(arg1 is SocketIOValue.Object)
            socket.disconnect()
        }

    // JS-088
    @Test
    fun onAnyPrependsListeners() =
        e2e {
            val count = AtomicInteger()
            val observed = CompletableDeferred<Int>()
            val socket =
                io("/abc", setup = {
                    onAny { _, _ -> observed.complete(count.get()) }
                    prependAny { _, _ -> check(count.getAndIncrement() == 1) }
                    prependAny { _, _ -> check(count.getAndIncrement() == 0) }
                })
            assertEquals(2, withTimeout(10.seconds) { observed.await() })
            socket.disconnect()
        }

    // JS-089
    @Test
    fun offAnyRemovesTheListener() =
        e2e {
            val unexpected = Unexpected()
            val done = CompletableDeferred<Unit>()
            val socket =
                io("/abc", setup = {
                    val fail = io.github.kaeferfreund.socketio.AnyListener { _, _ -> unexpected.fail("removed listener called") }
                    onAny(fail)
                    offAny(fail)
                    check(listenersAny().isEmpty())
                    onAny { _, _ -> done.complete(Unit) }
                })
            withTimeout(10.seconds) { done.await() }
            socket.disconnect()
            unexpected.check()
        }

    // JS-090
    @Test
    fun onAnyOutgoingCallsTheListener() =
        e2e {
            val sent = CompletableDeferred<Pair<String, SocketIOValue?>>()
            val socket =
                io("/abc", setup = {
                    onConnect {
                        onAnyOutgoing { name, args -> sent.complete(name to args.firstOrNull()) }
                        emit("my-event", "123")
                    }
                })
            assertEquals("my-event" to SocketIOValue.Text("123"), withTimeout(10.seconds) { sent.await() })
            socket.disconnect()
        }

    // JS-091
    @Test
    fun onAnyOutgoingCallsTheListenerWithBinaryData() =
        e2e {
            val sent = CompletableDeferred<Pair<String, SocketIOValue?>>()
            val socket =
                io("/abc", setup = {
                    onConnect {
                        onAnyOutgoing { name, args -> sent.complete(name to args.firstOrNull()) }
                        emit("my-event", byteArrayOf(1, 2, 3))
                    }
                })
            val (name, arg) = withTimeout(10.seconds) { sent.await() }
            assertEquals("my-event", name)
            assertEquals(SocketIOValue.Binary(byteArrayOf(1, 2, 3)), arg)
            socket.disconnect()
        }

    // JS-092
    @Test
    fun onAnyOutgoingPrependsListeners() =
        e2e {
            val count = AtomicInteger()
            val observed = CompletableDeferred<Int>()
            val socket =
                io("/abc", setup = {
                    onAnyOutgoing { _, _ -> observed.complete(count.get()) }
                    prependAnyOutgoing { _, _ -> check(count.getAndIncrement() == 1) }
                    prependAnyOutgoing { _, _ -> check(count.getAndIncrement() == 0) }
                })
            socket.emit("my-event", "123")
            assertEquals(2, withTimeout(10.seconds) { observed.await() })
            socket.disconnect()
        }

    // JS-093
    @Test
    fun offAnyOutgoingRemovesTheListener() =
        e2e {
            val unexpected = Unexpected()
            val done = CompletableDeferred<Unit>()
            val socket = io("/abc")
            val fail = io.github.kaeferfreund.socketio.AnyListener { _, _ -> unexpected.fail("removed listener called") }
            socket.onAnyOutgoing(fail)
            socket.offAnyOutgoing(fail)
            assertTrue(socket.listenersAnyOutgoing().isEmpty())
            socket.onAnyOutgoing { _, _ -> done.complete(Unit) }
            socket.emit("my-event", "123")
            withTimeout(10.seconds) { done.await() }
            socket.disconnect()
            unexpected.check()
        }

    // JS-094
    @Test
    fun timesOutAfterTheGivenDelayWhenNotConnected() =
        e2e {
            val socket = io("/") { autoConnect = false }
            val result = await<Result<List<SocketIOValue>>> { d -> socket.timeout(50.milliseconds).emit("event") { d.complete(it) } }
            assertTrue(result.exceptionOrNull() is AckTimeoutException)
            assertTrue(socket.sendBufferSnapshot().isEmpty())
            socket.disconnect()
        }

    // JS-095
    @Test
    fun timesOutWhenTheServerDoesNotAcknowledge() =
        e2e {
            val socket = io("/")
            val result = await<Result<List<SocketIOValue>>> { d -> socket.timeout(50.milliseconds).emit("unknown") { d.complete(it) } }
            assertTrue(result.exceptionOrNull() is AckTimeoutException)
            socket.disconnect()
        }

    // JS-096
    @Test
    fun timesOutWhenTheServerDoesNotAcknowledgeInTime() =
        e2e {
            val socket = io("/")
            val count = AtomicInteger()
            val errors = CopyOnWriteArrayList<Throwable?>()
            socket.timeout(Duration.ZERO).emit("echo", 42) { result ->
                errors += result.exceptionOrNull()
                count.incrementAndGet()
            }
            delay(200.milliseconds)
            assertEquals(1, count.get())
            assertTrue(errors.single() is AckTimeoutException)
            socket.disconnect()
        }

    // JS-097: connected and upgraded first, so the 50 ms budget covers the roundtrip,
    // not JVM warm-up or the upgrade pause.
    @Test
    fun doesNotTimeOutWhenTheServerAcknowledges() =
        e2e {
            val socket = io("/")
            socket.awaitConnect()
            socket.manager.transportName.first { it == Transport.WEBSOCKET }
            val result = await<Result<List<SocketIOValue>>> { d -> socket.timeout(50.milliseconds).emit("echo", 42) { d.complete(it) } }
            assertEquals(42L, result.getOrThrow()[0].long)
            socket.disconnect()
        }

    // JS-098
    @Test
    fun timesOutWhenTheServerDoesNotAcknowledgeWithEmitWithAck() =
        e2e {
            val socket = io("/")
            assertThrows<AckTimeoutException> { kotlinx.coroutines.runBlocking { socket.timeout(50.milliseconds).emitWithAck("unknown") } }
            socket.disconnect()
        }

    // JS-099: connected and upgraded first, as in JS-097.
    @Test
    fun doesNotTimeOutWhenTheServerAcknowledgesWithEmitWithAck() =
        e2e {
            val socket = io("/")
            socket.awaitConnect()
            socket.manager.transportName.first { it == Transport.WEBSOCKET }
            assertEquals(42L, socket.timeout(50.milliseconds).emitWithAck("echo", 42)[0].long)
            socket.disconnect()
        }

    // JS-100
    @Test
    fun usesTheDefaultTimeoutValue() =
        e2e {
            val socket = io("/", SocketOptions { ackTimeout = 50.milliseconds })
            val result = await<Result<List<SocketIOValue>>> { d -> socket.emit("unknown") { d.complete(it) } }
            assertTrue(result.exceptionOrNull() is AckTimeoutException)
            socket.disconnect()
        }

    // JS-101
    @Test
    fun doesNotAckUponDisconnectionWithACallback() =
        e2e {
            val unexpected = Unexpected()
            val socket = io()
            socket.awaitConnect()
            val pending = CompletableDeferred<Int>()
            socket.manager.executor.execute {
                socket.emit("echo", "a") { unexpected.fail("ack after disconnect") }
                socket.disconnect()
                pending.complete(socket.pendingAcknowledgements.value)
            }
            assertEquals(0, withTimeout(10.seconds) { pending.await() })
            delay(100.milliseconds)
            unexpected.check()
        }

    // JS-102
    @Test
    fun acksWithAnErrorUponDisconnectionWithCallbackAndTimeout() =
        e2e {
            val socket = io()
            socket.awaitConnect()
            val outcome = CompletableDeferred<Pair<Throwable?, Int>>()
            socket.manager.executor.execute {
                socket.timeout(10.seconds).emit("echo", "a") { outcome.complete(it.exceptionOrNull() to socket.pendingAcknowledgements.value) }
                socket.disconnect()
            }
            val (error, acks) = withTimeout(10.seconds) { outcome.await() }
            assertTrue(error is SocketDisconnectedException)
            assertEquals(0, acks)
        }

    // JS-103
    @Test
    fun acksWithAnErrorUponDisconnectionWithCallbackAndAckTimeout() =
        e2e {
            val socket = io(socketOptions = SocketOptions { ackTimeout = 10.seconds })
            socket.awaitConnect()
            val outcome = CompletableDeferred<Throwable?>()
            socket.manager.executor.execute {
                socket.emit("echo", "a") { outcome.complete(it.exceptionOrNull()) }
                socket.disconnect()
            }
            assertTrue(withTimeout(10.seconds) { outcome.await() } is SocketDisconnectedException)
        }

    // JS-104
    @Test
    fun acksWithAnErrorUponDisconnectionWithEmitWithAck() =
        e2e {
            val socket = io()
            socket.awaitConnect()
            val outcome = CompletableDeferred<Throwable?>()
            // emitWithAck() then disconnect(), in this order, as JavaScript runs them synchronously.
            socket.manager.executor.execute {
                socket.manager.executor.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                    outcome.complete(runCatching { socket.emitWithAck("echo", "a") }.exceptionOrNull())
                }
                socket.disconnect()
            }
            assertTrue(withTimeout(10.seconds) { outcome.await() } is SocketDisconnectedException)
        }

    // JS-105
    @Test
    fun acksWithAnErrorUponDisconnectionWithEmitWithAckAndTimeout() =
        e2e {
            val socket = io()
            socket.awaitConnect()
            val outcome = CompletableDeferred<Throwable?>()
            socket.manager.executor.execute {
                socket.manager.executor.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                    outcome.complete(runCatching { socket.timeout(10.seconds).emitWithAck("echo", "a") }.exceptionOrNull())
                }
                socket.disconnect()
            }
            assertTrue(withTimeout(10.seconds) { outcome.await() } is SocketDisconnectedException)
        }

    // JS-106
    @Test
    fun doesNotDiscardAnUnsentAck() =
        e2e {
            val socket = io()
            socket.awaitConnect()
            socket.disconnect()
            val value =
                await<String?> { d ->
                    socket.emit("echo", "a") { d.complete(it.getOrThrow()[0].string) }.also {
                        kotlinx.coroutines.runBlocking { delay(100) }
                        socket.connect()
                    }
                }
            assertEquals("a", value)
            socket.disconnect()
        }

    // JS-107: the heartbeat deadline passed while timers were throttled (a sleeping device).
    @Test
    fun buffersAnEventWhenTheHeartbeatExpiredAndSendsItAfterReconnecting() =
        e2e {
            val clock = kotlin.time.TestTimeSource()
            val hasReconnected = java.util.concurrent.atomic.AtomicBoolean(false)
            val socket =
                io(setup = { manager.on<ManagerEvent.Reconnect> { hasReconnected.set(true) } }) {
                    reconnectionDelay = 10.milliseconds
                    timeSource = clock
                }
            socket.awaitConnect()
            val result = CompletableDeferred<Pair<Boolean, String?>>()
            socket.manager.executor.execute {
                // Simulate a throttled timer: the deadline (pingInterval + pingTimeout) lies in the past.
                clock += 1.seconds * 3600
                socket.emit("echo", "123") { result.complete(hasReconnected.get() to it.getOrThrow()[0].string) }
            }
            val (reconnected, value) = withTimeout(20.seconds) { result.await() }
            assertTrue(reconnected)
            assertEquals("123", value)
            socket.disconnect()
        }
}
