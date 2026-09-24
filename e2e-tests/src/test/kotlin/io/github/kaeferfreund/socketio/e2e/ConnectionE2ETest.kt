package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.ManagerEvent
import io.github.kaeferfreund.socketio.SocketIO
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Ports of `packages/socket.io-client/test/connection.ts` against the ported upstream test server. */
class ConnectionE2ETest {
    // JS-002
    @Test
    fun connectsToLocalhost() =
        e2e {
            val socket = io()
            socket.emit("hi")
            socket.channel("hi").next()
            socket.disconnect()
        }

    // JS-003
    @Test
    fun doesNotConnectWhenAutoConnectIsFalse() =
        e2e {
            val socket = io { autoConnect = false }
            delay(100.milliseconds)
            assertFalse(socket.active)
            assertEquals(null, socket.manager.transportName.value)
            assertEquals(0, server.admin("/admin/stats", "GET").second.let { Regex("\"engines\":(\\d+)").find(it)!!.groupValues[1].toInt() })
            socket.disconnect()
        }

    // JS-004
    @Test
    fun startsTwoConnectionsWithTheSamePath() =
        e2e {
            val s1 = SocketIO.io("$url/")
            val s2 = SocketIO.io("$url/")
            managers += listOf(s1.manager, s2.manager)
            assertNotSame(s1.manager, s2.manager)
            s1.disconnect()
            s2.disconnect()
        }

    // JS-005
    @Test
    fun startsTwoConnectionsWithTheSamePathAndDifferentQueryStrings() =
        e2e {
            val s1 = SocketIO.io("$url/?woot")
            val s2 = SocketIO.io("$url/")
            managers += listOf(s1.manager, s2.manager)
            assertNotSame(s1.manager, s2.manager)
            s1.disconnect()
            s2.disconnect()
        }

    // JS-006
    @Test
    fun startsTwoConnectionsWithDifferentPaths() =
        e2e {
            val s1 = SocketIO.io("$url/", SocketManagerOptions { path = "/foo" })
            val s2 = SocketIO.io("$url/", SocketManagerOptions { path = "/bar" })
            managers += listOf(s1.manager, s2.manager)
            assertNotSame(s1.manager, s2.manager)
            s1.disconnect()
            s2.disconnect()
        }

    // JS-007
    @Test
    fun startsASingleConnectionWithDifferentNamespaces() =
        e2e {
            val options = SocketManagerOptions()
            val s1 = SocketIO.io("$url/foo", options)
            val s2 = SocketIO.io("$url/bar", options)
            managers += s1.manager
            assertSame(s1.manager, s2.manager)
            s1.disconnect()
            s2.disconnect()
        }

    // JS-008
    @Test
    fun worksWithAcks() =
        e2e {
            val socket = io()
            socket.emit("ack")
            socket.on("ack") { event -> event.ack!!.send(5, mapOf("test" to true)) }
            socket.channel("got it").next()
            socket.disconnect()
        }

    // JS-009
    @Test
    fun receivesADateWithAck() =
        e2e {
            val socket = io()
            val reply = await<List<SocketIOValue>> { d -> socket.emit("getAckDate", mapOf("test" to true)) { d.complete(it.getOrThrow()) } }
            assertTrue(reply[0] is SocketIOValue.Text)
            socket.disconnect()
        }

    // JS-010
    @Test
    fun worksWithFalse() =
        e2e {
            val socket = io()
            val events = socket.channel("false")
            socket.emit("false")
            assertEquals(false, events.next()[0]!!.boolean)
            socket.disconnect()
        }

    // JS-011
    @Test
    fun receivesUtf8MultibyteCharacters() =
        e2e {
            val correct = listOf("てすと", "Я Б Г Д Ж Й", "Ä ä Ü ü ß", "utf8 — string", "utf8 — string")
            val socket = io()
            val events = socket.channel("takeUtf8")
            socket.emit("getUtf8")
            assertEquals(correct, correct.map { events.next()[0]!!.string })
            socket.disconnect()
        }

    // JS-012
    @Test
    fun connectsToANamespaceAfterTheConnectionIsEstablished() =
        e2e {
            val manager = manager()
            val socket = manager.socket("/")
            socket.awaitConnect()
            val foo = manager.socket("/foo")
            foo.awaitConnect()
            foo.close()
            socket.close()
            manager.disconnect()
        }

    // JS-013
    @Test
    fun opensANewNamespaceAfterTheConnectionGetsClosed() =
        e2e {
            val manager = manager()
            val done = CompletableDeferred<Unit>()
            manager.socket("/") {
                onConnect { disconnect() }
                onDisconnect { _, _ ->
                    manager.socket("/foo") {
                        onConnect {
                            disconnect()
                            manager.disconnect()
                            done.complete(Unit)
                        }
                    }
                }
            }
            withTimeout(10.seconds) { done.await() }
        }

    // JS-014
    @Test
    fun reconnectsByDefault() =
        e2e {
            val socket = io { reconnectionDelay = 10.milliseconds }
            val reconnected = CompletableDeferred<Unit>()
            socket.manager.on<ManagerEvent.Reconnect> { reconnected.complete(Unit) }
            socket.awaitConnect()
            killTransport(socket)
            withTimeout(10.seconds) { reconnected.await() }
            socket.disconnect()
        }

    // JS-015
    @Test
    fun reconnectsManually() =
        e2e {
            val socket = io()
            socket.awaitConnect()
            val disconnected = CompletableDeferred<Unit>()
            socket.onDisconnect { _, _ -> disconnected.complete(Unit) }
            socket.disconnect()
            withTimeout(10.seconds) { disconnected.await() }
            socket.connect()
            socket.awaitConnect()
            socket.disconnect()
        }

    // JS-016
    @Test
    fun reconnectsAutomaticallyAfterReconnectingManually() =
        e2e {
            val reconnected = CompletableDeferred<Unit>()
            val reconnectedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var socket: io.github.kaeferfreund.socketio.Socket
            socket =
                io(setup = {
                    val phase = AtomicInteger()
                    onConnect {
                        when (phase.getAndIncrement()) {
                            0 -> disconnect()

                            // Connected again manually: now lose the transport.
                            1 -> killTransport(socket)
                        }
                    }
                    onDisconnect { reason, _ ->
                        if (reason == io.github.kaeferfreund.socketio.DisconnectReason.IO_CLIENT_DISCONNECT && !reconnectedOnce.getAndSet(true)) {
                            manager.on<ManagerEvent.Reconnect> { reconnected.complete(Unit) }
                            connect()
                        }
                    }
                }) { reconnectionDelay = 10.milliseconds }
            withTimeout(10.seconds) { reconnected.await() }
            socket.disconnect()
        }

    // JS-017: `timeout: 0` fails every open attempt at once.
    @Test
    fun attemptsReconnectsAfterAFailedReconnect() =
        e2e {
            val firstFailure = CompletableDeferred<Unit>()
            lateinit var sub: io.github.kaeferfreund.socketio.Subscription
            val manager =
                manager(setup = { sub = on<ManagerEvent.ReconnectFailed> { firstFailure.complete(Unit) } }) {
                    reconnection = true
                    timeout = Duration.ZERO
                    reconnectionAttempts = 2
                    reconnectionDelay = 10.milliseconds
                }
            val socket = manager.socket("/timeout")
            withTimeout(10.seconds) { firstFailure.await() }
            sub.cancel()
            val reconnects = AtomicInteger()
            manager.on<ManagerEvent.ReconnectAttempt> { reconnects.incrementAndGet() }
            val secondFailure = CompletableDeferred<Unit>()
            manager.on<ManagerEvent.ReconnectFailed> { secondFailure.complete(Unit) }
            socket.connect()
            withTimeout(10.seconds) { secondFailure.await() }
            assertEquals(2, reconnects.get())
            socket.close()
            manager.disconnect()
        }

    // JS-018
    @Test
    fun reconnectDelayIncreasesEveryTime() =
        e2e {
            val reconnects = AtomicInteger()
            val increasing = java.util.concurrent.atomic.AtomicBoolean(true)
            var startTime = 0L
            var previousDelay = 0L
            val failed = CompletableDeferred<Unit>()
            val manager =
                manager(
                    setup = {
                        on<ManagerEvent.Error> { startTime = System.nanoTime() }
                        on<ManagerEvent.ReconnectAttempt> {
                            reconnects.incrementAndGet()
                            val delay = (System.nanoTime() - startTime) / 1_000_000
                            if (delay <= previousDelay) increasing.set(false)
                            previousDelay = delay
                        }
                        on<ManagerEvent.ReconnectFailed> { failed.complete(Unit) }
                    },
                ) {
                    reconnection = true
                    timeout = Duration.ZERO
                    reconnectionAttempts = 3
                    reconnectionDelay = 100.milliseconds
                    randomizationFactor = 0.2
                }
            val socket = manager.socket("/timeout")
            withTimeout(10.seconds) { failed.await() }
            assertEquals(3, reconnects.get())
            assertTrue(increasing.get())
            socket.close()
            manager.disconnect()
        }

    // JS-019
    @Test
    fun doesNotReconnectWhenForceClosed() =
        e2e {
            val unexpected = Unexpected()
            val errored = CompletableDeferred<Unit>()
            lateinit var socket: io.github.kaeferfreund.socketio.Socket
            val manager =
                manager(
                    setup = {
                        on<ManagerEvent.Error> {
                            if (errored.isCompleted) return@on
                            on<ManagerEvent.ReconnectAttempt> { unexpected.fail("reconnect_attempt") }
                            socket.disconnect()
                            errored.complete(Unit)
                        }
                    },
                ) {
                    autoConnect = false
                    timeout = Duration.ZERO
                    reconnectionDelay = 10.milliseconds
                }
            socket = manager.socket("/invalid")
            socket.connect()
            withTimeout(10.seconds) { errored.await() }
            delay(100.milliseconds)
            unexpected.check()
        }

    // JS-020
    @Test
    fun stopsReconnectingWhenForceClosed() =
        e2e {
            val unexpected = Unexpected()
            val first = CompletableDeferred<Unit>()
            lateinit var socket: io.github.kaeferfreund.socketio.Socket
            val manager =
                manager(
                    setup = {
                        on<ManagerEvent.ReconnectAttempt> {
                            if (first.isCompleted) {
                                unexpected.fail("reconnect_attempt after disconnect")
                                return@on
                            }
                            socket.disconnect()
                            first.complete(Unit)
                        }
                    },
                ) {
                    autoConnect = false
                    timeout = Duration.ZERO
                    reconnectionDelay = 10.milliseconds
                }
            socket = manager.socket("/invalid")
            socket.connect()
            withTimeout(10.seconds) { first.await() }
            delay(100.milliseconds)
            unexpected.check()
        }

    // JS-021
    @Test
    fun reconnectsAfterStoppingReconnection() =
        e2e {
            val attempts = AtomicInteger()
            val done = CompletableDeferred<Unit>()
            lateinit var socket: io.github.kaeferfreund.socketio.Socket
            val manager =
                manager(
                    setup = {
                        on<ManagerEvent.ReconnectAttempt> {
                            if (attempts.incrementAndGet() == 1) {
                                socket.disconnect()
                                socket.connect()
                            } else {
                                socket.disconnect()
                                done.complete(Unit)
                            }
                        }
                    },
                ) {
                    autoConnect = false
                    timeout = Duration.ZERO
                    reconnectionDelay = 10.milliseconds
                }
            socket = manager.socket("/invalid")
            socket.connect()
            withTimeout(10.seconds) { done.await() }
        }

    // JS-022
    @Test
    fun stopsReconnectingOnASocketAndKeepsReconnectingOnAnother() =
        e2e {
            val manager = manager { reconnectionDelay = 10.milliseconds }
            val socket1 = manager.socket("/")
            val socket2 = manager.socket("/asd")
            socket1.awaitConnect()
            socket2.awaitConnect()
            val unexpected = Unexpected()
            val done = CompletableDeferred<Unit>()
            manager.on<ManagerEvent.ReconnectAttempt> {
                socket1.onConnect { unexpected.fail("socket1 reconnected") }
                socket2.onConnect { done.complete(Unit) }
                socket1.disconnect()
            }
            killTransport(socket1)
            withTimeout(10.seconds) { done.await() }
            delay(50.milliseconds)
            socket2.disconnect()
            manager.disconnect()
            unexpected.check()
        }

    // JS-023
    @Test
    fun triesToReconnectTwiceAndFailsWithImmediateTimeout() =
        e2e {
            val reconnects = AtomicInteger()
            val failed = CompletableDeferred<Unit>()
            val manager =
                manager(
                    setup = {
                        on<ManagerEvent.ReconnectAttempt> { reconnects.incrementAndGet() }
                        on<ManagerEvent.ReconnectFailed> { failed.complete(Unit) }
                    },
                ) {
                    reconnection = true
                    timeout = Duration.ZERO
                    reconnectionAttempts = 2
                    reconnectionDelay = 10.milliseconds
                }
            val socket = manager.socket("/timeout")
            withTimeout(10.seconds) { failed.await() }
            assertEquals(2, reconnects.get())
            socket.close()
            manager.disconnect()
        }

    // JS-024, JS-025
    @Test
    fun firesReconnectEventsWithAttemptNumbers() =
        e2e {
            val attempts = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val failed = CompletableDeferred<Unit>()
            val manager =
                manager(
                    setup = {
                        on<ManagerEvent.ReconnectAttempt> { attempts.add(it.attempt) }
                        on<ManagerEvent.ReconnectFailed> { failed.complete(Unit) }
                    },
                ) {
                    reconnection = true
                    timeout = Duration.ZERO
                    reconnectionAttempts = 2
                    reconnectionDelay = 10.milliseconds
                }
            val socket = manager.socket("/timeout_socket")
            withTimeout(10.seconds) { failed.await() }
            assertEquals(listOf(1, 2), attempts)
            socket.close()
            manager.disconnect()
        }

    // JS-026
    @Test
    fun doesNotReconnectWhenConnectingToTheCorrectPortWithDefaultTimeout() =
        e2e {
            val unexpected = Unexpected()
            val manager =
                manager(setup = { on<ManagerEvent.ReconnectAttempt> { unexpected.fail("reconnect_attempt") } }) {
                    reconnection = true
                    reconnectionDelay = 10.milliseconds
                }
            val socket = manager.socket("/valid")
            socket.awaitConnect()
            delay(100.milliseconds)
            socket.close()
            manager.disconnect()
            unexpected.check()
        }

    // JS-027
    @Test
    fun connectsWhileDisconnectingAnotherSocket() =
        e2e {
            val manager = manager()
            val done = CompletableDeferred<Unit>()
            manager.socket("/foo") {
                onConnect {
                    manager.socket("/asd") { onConnect { done.complete(Unit) } }
                    disconnect()
                }
            }
            withTimeout(10.seconds) { done.await() }
        }

    // JS-029
    @Test
    fun doesNotCloseTheConnectionWhenDisconnectingASingleSocket() =
        e2e {
            val manager = manager { autoConnect = false }
            val socket1 = manager.socket("/foo")
            val socket2 = manager.socket("/asd")
            socket1.onConnect { socket2.connect() }
            socket1.connect()
            socket2.awaitConnect()
            val unexpected = Unexpected()
            val sub = socket2.onDisconnect { _, _ -> unexpected.fail("socket2 disconnected") }
            socket1.disconnect()
            delay(200.milliseconds)
            unexpected.check()
            sub.cancel()
            val closed = CompletableDeferred<Unit>()
            manager.on<ManagerEvent.Close> { closed.complete(Unit) }
            socket2.disconnect()
            withTimeout(10.seconds) { closed.await() }
        }

    // JS-030
    @Test
    fun stopsTryingToReconnect() =
        e2e {
            val unexpected = Unexpected()
            val stopped = CompletableDeferred<Unit>()
            val manager =
                manager(
                    "http://localhost:9823",
                    setup = {
                        on<ManagerEvent.ReconnectError> {
                            if (stopped.isCompleted) return@on
                            reconnection = false
                            on<ManagerEvent.ReconnectAttempt> { unexpected.fail("reconnect_attempt") }
                            stopped.complete(Unit)
                        }
                    },
                ) { reconnectionDelay = 10.milliseconds }
            withTimeout(10.seconds) { stopped.await() }
            delay(100.milliseconds)
            manager.disconnect()
            unexpected.check()
        }

    // JS-031
    @Test
    fun triesToReconnectTwiceAndFailsWithAnIncorrectAddress() =
        e2e {
            val reconnects = AtomicInteger()
            val failed = CompletableDeferred<Unit>()
            val manager =
                manager(
                    "http://localhost:3940",
                    setup = {
                        on<ManagerEvent.ReconnectAttempt> { reconnects.incrementAndGet() }
                        on<ManagerEvent.ReconnectFailed> { failed.complete(Unit) }
                    },
                ) {
                    reconnection = true
                    reconnectionAttempts = 2
                    reconnectionDelay = 10.milliseconds
                }
            val socket = manager.socket("/asd")
            withTimeout(10.seconds) { failed.await() }
            assertEquals(2, reconnects.get())
            socket.disconnect()
            manager.disconnect()
        }

    // JS-032
    @Test
    fun doesNotReconnectWithAnIncorrectPortWhenReconnectionIsDisabled() =
        e2e {
            val unexpected = Unexpected()
            val errored = CompletableDeferred<Unit>()
            val manager =
                manager(
                    "http://localhost:9823",
                    setup = {
                        on<ManagerEvent.ReconnectAttempt> { unexpected.fail("reconnect_attempt") }
                        on<ManagerEvent.Error> { errored.complete(Unit) }
                    },
                ) {
                    reconnection = false
                    reconnectionDelay = 10.milliseconds
                }
            val socket = manager.socket("/invalid")
            withTimeout(10.seconds) { errored.await() }
            delay(100.milliseconds)
            socket.disconnect()
            manager.disconnect()
            unexpected.check()
        }

    // JS-033
    @Test
    fun stillTriesToReconnectTwiceAfterOpeningAnotherSocketAsynchronously() =
        e2e {
            val reconnects = AtomicInteger()
            val failed = CompletableDeferred<Unit>()
            val manager =
                manager(
                    "http://localhost:9823",
                    setup = {
                        on<ManagerEvent.ReconnectAttempt> { reconnects.incrementAndGet() }
                        on<ManagerEvent.ReconnectFailed> { failed.complete(Unit) }
                    },
                ) {
                    reconnection = true
                    reconnectionAttempts = 2
                    reconnectionDelay = 10.milliseconds
                }
            val delayMs = maxOf((manager.reconnectionDelay.inWholeMilliseconds * manager.randomizationFactor * 0.5).toLong(), 10)
            val socket = manager.socket("/room1")
            delay(delayMs.milliseconds)
            manager.socket("/room2")
            withTimeout(10.seconds) { failed.await() }
            assertEquals(2, reconnects.get())
            socket.disconnect()
            manager.disconnect()
        }

    // JS-036
    @Test
    fun emitsADateAsString() =
        e2e {
            val socket = io()
            val events = socket.channel("takeDate")
            socket.emit("getDate")
            assertTrue(events.next()[0] is SocketIOValue.Text)
            socket.close()
        }

    // JS-037
    @Test
    fun emitsADateInAnObject() =
        e2e {
            val socket = io()
            val events = socket.channel("takeDateObj")
            socket.emit("getDateObj")
            val data = events.next()[0] as SocketIOValue.Object
            assertTrue(data["date"] is SocketIOValue.Text)
            socket.close()
        }

    // JS-038: binary arrives as binary on every transport, including with forceBase64.
    @Test
    fun getsBinaryDataAsALastResortWithForceBase64() =
        e2e {
            val socket = io { forceBase64 = true }
            val events = socket.channel("takebin")
            socket.emit("getbin")
            val data = events.next()[0]!!
            assertEquals("asdfasdf", data.bytes!!.decodeToString())
            socket.disconnect()
        }

    // JS-039
    @Test
    fun getsBinaryData() =
        e2e {
            val socket = io()
            val events = socket.channel("doge")
            socket.emit("doge")
            assertTrue(events.next()[0] is SocketIOValue.Binary)
            socket.disconnect()
        }

    // JS-040
    @Test
    fun sendsBinaryData() =
        e2e {
            val socket = io()
            val ack = socket.channel("buffack")
            socket.emit("buffa", "asdfasdf".encodeToByteArray())
            ack.next()
            socket.disconnect()
        }

    // JS-041
    @Test
    fun sendsBinaryDataMixedWithJson() =
        e2e {
            val socket = io()
            val ack = socket.channel("jsonbuff-ack")
            socket.emit("jsonbuff", mapOf("hello" to "lol", "message" to "howdy".encodeToByteArray(), "goodbye" to "gotcha"))
            ack.next()
            socket.disconnect()
        }

    // JS-042
    @Test
    fun sendsEventsWithBinaryInTheCorrectOrder() =
        e2e {
            val socket = io()
            val ack = socket.channel("abuff2-ack")
            socket.emit("abuff1", "abuff1".encodeToByteArray())
            socket.emit("abuff2", "please arrive second")
            ack.next()
            socket.disconnect()
        }

    // JS-043: a browser Blob is binary content; ByteArray is its native counterpart.
    @Test
    fun sendsBinaryDataAsABlob() =
        e2e {
            val socket = io()
            val back = socket.channel("back")
            socket.emit("blob", "hello world".encodeToByteArray())
            back.next()
            socket.disconnect()
        }

    // JS-044
    @Test
    fun sendsBinaryDataAsABlobMixedWithJson() =
        e2e {
            val socket = io()
            val ack = socket.channel("jsonblob-ack")
            socket.emit("jsonblob", mapOf("hello" to "lol", "message" to "EEEEEEEEE".encodeToByteArray(), "goodbye" to "gotcha"))
            ack.next()
            socket.disconnect()
        }

    // JS-045
    @Test
    fun sendsEventsWithBlobsInTheCorrectOrder() =
        e2e {
            val socket = io()
            val ack = socket.channel("blob3-ack")
            val blob = "BLOBBLOB".encodeToByteArray()
            socket.emit("blob1", blob)
            socket.emit("blob2", "second")
            socket.emit("blob3", blob)
            ack.next()
            socket.disconnect()
        }

    // JS-046
    @Test
    fun reopensACachedSocket() =
        e2e {
            val manager = manager { autoConnect = true }
            val done = CompletableDeferred<Unit>()
            val checks = Channel<Boolean>(Channel.UNLIMITED)
            lateinit var socket: io.github.kaeferfreund.socketio.Socket
            socket =
                manager.socket("/") {
                    onConnect { disconnect() }
                    onDisconnect { _, _ ->
                        val socket2 = manager.socket("/")
                        checks.trySend(socket2 === socket)
                        checks.trySend(socket2.active)
                        socket2.onConnect {
                            socket2.disconnect()
                            done.complete(Unit)
                        }
                    }
                }
            withTimeout(10.seconds) { done.await() }
            assertTrue(checks.next())
            assertTrue(checks.next())
        }

    // JS-047
    @Test
    fun doesNotReopenACachedButActiveSocket() =
        e2e {
            val created = java.util.concurrent.CopyOnWriteArrayList<String>()
            val done = CompletableDeferred<Boolean>()
            manager(
                setup = {
                    on<ManagerEvent.Open> {
                        val socket = socket("/")
                        val socket2 = socket("/")
                        val same = socket2 === socket
                        socket.onConnect {
                            socket.disconnect()
                            done.complete(same)
                        }
                    }
                },
            ) {
                autoConnect = true
                packetObserver =
                    io.github.kaeferfreund.socketio.engineio.EnginePacketObserver { outgoing, packet -> if (outgoing) created += packet.text.orEmpty() }
            }
            assertTrue(withTimeout(10.seconds) { done.await() })
            delay(50.milliseconds)
            assertEquals(listOf("0", "1"), created.filter { it.isNotEmpty() })
        }

    // JS-048
    @Test
    fun doesNotReopenAnAlreadyActiveSocket() =
        e2e {
            val created = java.util.concurrent.CopyOnWriteArrayList<String>()
            val done = CompletableDeferred<Unit>()
            lateinit var manager: io.github.kaeferfreund.socketio.SocketManager
            manager =
                manager(
                    setup = {
                        on<ManagerEvent.Open> { onOpenCheck(manager, done) }
                    },
                ) {
                    autoConnect = true
                    packetObserver =
                        io.github.kaeferfreund.socketio.engineio.EnginePacketObserver { outgoing, packet -> if (outgoing) created += packet.text.orEmpty() }
                }
            withTimeout(10.seconds) { done.await() }
            delay(50.milliseconds)
            assertEquals(listOf("0", "0/foo,", "1", "1/foo,"), created.filter { it.isNotEmpty() })
        }

    private fun onOpenCheck(
        manager: io.github.kaeferfreund.socketio.SocketManager,
        done: CompletableDeferred<Unit>,
    ) {
        run {
            val socket = manager.socket("/")
            val socketFoo = manager.socket("/foo")
            // JavaScript relies on /foo's CONNECT reply arriving first; the order of the
            // two replies is the server's, so wait for both before disconnecting.
            val both = AtomicInteger()
            val onBoth = {
                if (both.incrementAndGet() == 2) {
                    socket.disconnect()
                    socketFoo.disconnect()
                    done.complete(Unit)
                }
            }
            socket.onConnect { onBoth() }
            socketFoo.onConnect { onBoth() }
        }
    }
}
