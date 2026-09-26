package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource as KotlinTestTimeSource

/**
 * Engine behaviour against the in-memory server, on virtual time. Ports of
 * `packages/engine.io-client/test/socket.js`, `connection.js`, `transport.js`
 * and `node.js` where the assertion does not need a real network (those are
 * repeated against a real Node server in the E2E suite).
 */
class EngineSocketTest {
    // JS-145 (unit counterpart)
    @Test
    fun connectsOverPollingAndReceivesTheGreeting() =
        runTest {
            val h = engineHarness()
            val engine = h.engine(EngineOptions(transports = listOf("polling")))
            h.settle()
            assertEquals(EngineState.OPEN, engine.readyState)
            assertEquals("sid0", engine.id)
            assertEquals("polling", engine.transport!!.name)
            assertEquals(listOf("hi"), h.messages)
            assertEquals(1, h.all<EngineEvent.Open>().size)
            val handshake = h.all<EngineEvent.HandshakeReceived>().single().handshake
            assertEquals(25.seconds, handshake.pingInterval)
            assertEquals(1_000_000L, handshake.maxPayload)
            h.close()
        }

    // JS-146 (unit counterpart)
    @Test
    fun connectsOverWebSocketOnly() =
        runTest {
            val h = engineHarness()
            val engine = h.engine(EngineOptions(transports = listOf("websocket")))
            h.settle()
            assertEquals(EngineState.OPEN, engine.readyState)
            assertEquals("websocket", engine.transport!!.name)
            assertEquals(listOf("hi"), h.messages)
            h.close()
        }

    // JS-147, JS-148
    @Test
    fun echoesMultibyteTextAndEmoji() =
        runTest {
            val h = engineHarness()
            val engine = h.engine(EngineOptions(transports = listOf("polling")))
            h.settle()
            val emoji = "𐀀-󯿿󰀀-􏿿-"
            h.executor.execute {
                engine.send("cash money €€€")
                engine.send(emoji)
            }
            h.settle()
            assertEquals(listOf("hi", "cash money €€€", emoji), h.messages)
            h.close()
        }

    // JS-149
    @Test
    fun doesNotSendPacketsIfTheSocketCloses() =
        runTest {
            val h = engineHarness()
            val engine = h.engine()
            h.settle()
            var created = 0
            h.executor.execute {
                engine.events.on(EngineEvent.PacketCreated::class.java) { created++ }
                engine.close()
                engine.send("hi")
            }
            advanceTimeBy(200.milliseconds)
            assertEquals(0, created)
            assertEquals(EngineState.CLOSED, engine.readyState)
            h.close()
        }

    // JS-150
    @Test
    fun mergesPacketsAccordingToMaxPayload() =
        runTest {
            val h = engineHarness(maxPayload = 100)
            val engine = h.engine(EngineOptions(transports = listOf("polling"), upgrade = false))
            h.settle()
            h.executor.execute {
                for (text in listOf("a".repeat(99), "b".repeat(30), "c".repeat(30), "d".repeat(35), "€".repeat(33), "f".repeat(99))) {
                    engine.send(text)
                }
            }
            h.settle()
            val posts = h.server.requests.filter { it.method == "POST" }.map { it.body!! }
            // 3 * 1 (type) + 2 (separators) + 30 + 30 + 35 = 100 fits; €€€ (99 bytes) goes alone.
            assertEquals(
                listOf(
                    "4" + "a".repeat(99),
                    "4" + "b".repeat(30) + "\u001e4" + "c".repeat(30) + "\u001e4" + "d".repeat(35),
                    "4" + "€".repeat(33),
                    "4" + "f".repeat(99),
                ),
                posts,
            )
            assertEquals(7, h.messages.size)
            h.close()
        }

    // JS-151
    @Test
    fun sendsAPacketAboveMaxPayloadAnyway() =
        runTest {
            val h = engineHarness(maxPayload = 100)
            val engine = h.engine(EngineOptions(transports = listOf("polling"), upgrade = false))
            h.settle()
            h.executor.execute {
                engine.send("a".repeat(101))
                engine.send("b")
            }
            h.settle()
            assertEquals("4" + "a".repeat(101), h.server.requests.first { it.method == "POST" }.body)
            // The server refuses the oversized body and the engine closes.
            assertEquals("transport error", h.all<EngineEvent.Close>().single().reason)
            h.close()
        }

    // JS-134, JS-141, JS-177: binary counts with the Base64 overhead (×1.33).
    @Test
    fun mergesBinaryPacketsAccordingToMaxPayload() =
        runTest {
            val h = engineHarness(maxPayload = 100)
            val engine = h.engine(EngineOptions(transports = listOf("polling"), upgrade = false))
            h.settle()
            h.executor.execute {
                engine.send(EngineIOData.Binary(ByteArray(72)))
                engine.send(EngineIOData.Binary(ByteArray(20)))
                engine.send("a".repeat(20))
                engine.send(EngineIOData.Binary(ByteArray(20)))
                engine.send(EngineIOData.Binary(ByteArray(72)))
            }
            h.settle()
            val batches = h.server.requests.filter { it.method == "POST" }.map { it.body!!.split('\u001e').size }
            assertEquals(listOf(1, 3, 1), batches)
            assertEquals(5, h.server.sessions.values.single().messages.size)
            h.close()
        }

    // JS-184
    @Test
    fun filterUpgradesReturnsOnlyAvailableTransports() =
        runTest {
            val h = engineHarness()
            val engine = h.engine(EngineOptions(transports = listOf("polling")), open = false)
            assertEquals(listOf("polling"), engine.filterUpgrades(listOf("polling", "websocket")))
            h.close()
        }

    // JS-185
    @Test
    fun doesNotMutateTheCallersTransportsOption() =
        runTest {
            val h = engineHarness()
            h.server.allowRequest = { if (it.url.startsWith("ws")) 403 else null }
            val transports = listOf("websocket", "polling")
            val options = EngineOptions(transports = transports, tryAllTransports = true)
            val engine = h.engine(options)
            h.settle()
            assertEquals(listOf("polling"), engine.transports)
            assertEquals(listOf("websocket", "polling"), options.transports)
            assertEquals(listOf("websocket", "polling"), transports)
            h.close()
        }

    // JS-186, JS-192: JavaScript reports it as an error event on the next tick of its (fake) timers;
    // here the configured test dispatcher plays that role, so nothing is emitted before it runs.
    @Test
    fun reportsAnErrorWhenNoTransportsAreAvailable() =
        runTest {
            val h = engineHarness()
            h.engine(EngineOptions(transports = emptyList()))
            assertTrue(h.all<EngineEvent.Error>().isEmpty())
            h.settle()
            assertEquals("No transports available", h.all<EngineEvent.Error>().single().error.message)
            h.close()
        }

    // JS-187
    @Test
    fun connectsWithTheSecondTransportWhenTryAllTransportsIsTrueAndWebSocketFails() =
        runTest {
            val h = engineHarness()
            h.server.allowRequest = { if (it.query["transport"] == "websocket") 400 else null }
            val engine = h.engine(EngineOptions(transports = listOf("websocket", "polling"), tryAllTransports = true))
            h.settle()
            assertEquals(EngineState.OPEN, engine.readyState)
            assertEquals("polling", engine.transport!!.name)
            h.close()
        }

    // JS-188
    @Test
    fun connectsWithTheSecondTransportWhenTryAllTransportsIsTrueAndPollingFails() =
        runTest {
            val h = engineHarness()
            h.server.allowRequest = { if (it.query["transport"] == "polling") 400 else null }
            val engine = h.engine(EngineOptions(transports = listOf("polling", "websocket"), tryAllTransports = true))
            h.settle()
            assertEquals(EngineState.OPEN, engine.readyState)
            assertEquals("websocket", engine.transport!!.name)
            h.close()
        }

    // JS-189
    @Test
    fun doesNotTryTheSecondTransportByDefault() =
        runTest {
            val h = engineHarness()
            h.server.allowRequest = { if (it.query["transport"] == "polling") 400 else null }
            val engine = h.engine(EngineOptions(transports = listOf("polling", "websocket")))
            h.settle()
            assertEquals("xhr poll error", h.all<EngineEvent.Error>().single().error.message)
            assertEquals(EngineState.CLOSED, engine.readyState)
            h.close()
        }

    // JS-190
    @Test
    fun connectsWithACustomPollingTransportImplementation() =
        runTest {
            val h = engineHarness()
            var created = 0
            val custom =
                EngineTransport.Factory { options ->
                    created++
                    PollingTransport(options)
                }
            val engine = h.engine(EngineOptions(transports = listOf("polling"), transportFactories = mapOf("polling" to custom)))
            h.settle()
            assertEquals(EngineState.OPEN, engine.readyState)
            assertEquals("polling", engine.transport!!.name)
            assertEquals(1, created)
            h.close()
        }

    // JS-191
    @Test
    fun connectsWithACustomWebSocketTransportImplementation() =
        runTest {
            val h = engineHarness()
            var created = 0
            val custom =
                EngineTransport.Factory { options ->
                    created++
                    WebSocketTransport(options)
                }
            val engine = h.engine(EngineOptions(transports = listOf("websocket"), transportFactories = mapOf("websocket" to custom)))
            h.settle()
            assertEquals("websocket", engine.transport!!.name)
            assertEquals(1, created)
            h.close()
        }

    // JS-194
    @Test
    fun providesDetailsWhenMaxHttpBufferSizeIsReachedOverPolling() =
        runTest {
            val h = engineHarness(maxPayload = 100)
            val engine = h.engine(EngineOptions(transports = listOf("polling")))
            h.settle()
            h.executor.execute { engine.send("a".repeat(101)) }
            h.settle()
            val error = h.all<EngineEvent.Error>().single().error as TransportException
            assertEquals("TransportError", error.type)
            assertEquals("xhr post error", error.message)
            assertEquals(413, error.description)
            assertEquals("", error.responseBody)
            val close = h.all<EngineEvent.Close>().single()
            assertEquals("transport error", close.reason)
            assertTrue(close.description is EngineIOException)
            h.close()
        }

    // JS-195
    @Test
    fun providesDetailsWhenMaxHttpBufferSizeIsReachedOverWebSocket() =
        runTest {
            val h = engineHarness(maxPayload = 100)
            val engine = h.engine(EngineOptions(transports = listOf("websocket")))
            h.settle()
            h.executor.execute { engine.send("a".repeat(101)) }
            h.settle()
            val close = h.all<EngineEvent.Close>().single()
            assertEquals("transport close", close.reason)
            val details = close.description as CloseDetails
            assertEquals("websocket connection closed", details.description)
            assertEquals(1009, details.code)
            assertEquals("", details.reason)
            h.close()
        }

    // JS-196
    @Test
    fun providesDetailsWhenTheSessionIdIsUnknownOverPolling() =
        runTest {
            val h = engineHarness()
            h.engine(EngineOptions(transports = listOf("polling"), query = mapOf("sid" to "abc")))
            h.settle()
            val error = h.all<EngineEvent.Error>().single().error as TransportException
            assertEquals("xhr poll error", error.message)
            assertEquals(400, error.description)
            assertEquals("{\"code\":1,\"message\":\"Session ID unknown\"}", error.responseBody)
            assertEquals("transport error", h.all<EngineEvent.Close>().single().reason)
            h.close()
        }

    // JS-197
    @Test
    fun providesDetailsWhenTheSessionIdIsUnknownOverWebSocket() =
        runTest {
            val h = engineHarness()
            h.engine(EngineOptions(transports = listOf("websocket"), query = mapOf("sid" to "abc")))
            h.settle()
            val error = h.all<EngineEvent.Error>().single().error as TransportException
            assertEquals("websocket error", error.message)
            assertEquals(400, error.statusCode)
            assertEquals("transport error", h.all<EngineEvent.Close>().single().reason)
            h.close()
        }

    // JS-198: the time source runs ahead of the timers, as on a device whose timers were throttled.
    @Test
    fun detectsAThrottledHeartbeatTimerOnce() =
        runTest {
            val clock = KotlinTestTimeSource()
            val h = engineHarness(timeSource = clock)
            val engine = h.engine()
            assertFalse(engine.hasPingExpired())
            h.settle()
            assertFalse(engine.hasPingExpired())
            clock += 45.seconds + 1.milliseconds
            assertTrue(engine.hasPingExpired())
            assertTrue(engine.hasPingExpired())
            assertTrue(engine.hasPingExpired())
            h.settle()
            val closes = h.all<EngineEvent.Close>()
            assertEquals(listOf("ping timeout"), closes.map { it.reason })
            h.close()
        }

    @Test
    fun answersPingsAndClosesWithPingTimeoutWhenPingsStop() =
        runTest {
            val h = engineHarness(pingInterval = 1.seconds, pingTimeout = 500.milliseconds)
            val engine = h.engine(EngineOptions(transports = listOf("polling")))
            h.settle()
            advanceTimeBy(1.seconds)
            h.settle()
            assertEquals(1, h.all<EngineEvent.Ping>().size)
            val session = h.server.sessions.values.single()
            assertTrue(session.received.any { it.type == EngineIOPacketType.PONG })
            // A dropped polling connection fails the pending long poll: a transport error.
            h.server.sessions.values.single().kill()
            h.settle()
            assertEquals(listOf("transport error"), h.all<EngineEvent.Close>().map { it.reason })
            assertNull(engine.id)
            h.close()
        }

    @Test
    fun closesWithPingTimeoutWhenTheServerStopsPinging() =
        runTest {
            val h = engineHarness(pingInterval = 1.seconds, pingTimeout = 500.milliseconds, heartbeat = false)
            val engine = h.engine(EngineOptions(transports = listOf("websocket")))
            h.settle()
            // The deadline is armed at the handshake: pingInterval + pingTimeout.
            advanceTimeBy(1.seconds + 499.milliseconds)
            assertTrue(h.all<EngineEvent.Close>().isEmpty())
            advanceTimeBy(2.milliseconds)
            assertEquals(listOf("ping timeout"), h.all<EngineEvent.Close>().map { it.reason })
            assertEquals(EngineState.CLOSED, engine.readyState)
            h.close()
        }

    @Test
    fun aServerPingResetsTheDeadline() =
        runTest {
            val h = engineHarness(pingInterval = 1.seconds, pingTimeout = 500.milliseconds, heartbeat = false)
            h.engine(EngineOptions(transports = listOf("websocket")))
            h.settle()
            advanceTimeBy(1.seconds)
            h.server.sessions.values.single().ping()
            h.settle()
            advanceTimeBy(1.seconds)
            assertTrue(h.all<EngineEvent.Close>().isEmpty())
            advanceTimeBy(501.milliseconds)
            assertEquals(listOf("ping timeout"), h.all<EngineEvent.Close>().map { it.reason })
            h.close()
        }

    @Test
    fun rejectsAnInvalidHandshake() =
        runTest {
            val h = engineHarness()
            val bad = FakeHandshakeClient(backgroundScope, "0{\"sid\":\"\",\"upgrades\":[],\"pingInterval\":1,\"pingTimeout\":1}")
            val engine = EngineSocket("http://localhost", EngineOptions(clients = EngineClients(bad, null)), h.executor)
            engine.events.on(EngineEvent::class.java) { h.events.add(it) }
            h.executor.execute { engine.open() }
            h.settle()
            assertEquals("invalid handshake", h.all<EngineEvent.Error>().single().error.message)
            assertEquals("transport error", h.all<EngineEvent.Close>().single().reason)
            h.close()
        }

    @Test
    fun theDefaultPathAndQueryFollowJavaScript() =
        runTest {
            val h = engineHarness()
            h.engine(EngineOptions(transports = listOf("polling"), query = mapOf("a" to "b c")), uri = "http://localhost:3000")
            h.settle()
            val first = h.server.requests.first()
            assertTrue(first.url.startsWith("http://localhost:3000/engine.io/?a=b%20c&EIO=4&transport=polling&t="), first.url)
            // As in JavaScript (`opts.query = parsedUri.query`), a query in the URL replaces the option.
            h.engine(EngineOptions(transports = listOf("polling"), query = mapOf("a" to "b c")), uri = "http://localhost:3000/?x=1")
            h.settle()
            val second = h.server.requests.first { "x=1" in it.url }
            assertTrue(second.url.startsWith("http://localhost:3000/engine.io/?x=1&EIO=4&transport=polling&t="), second.url)
            h.close()
        }

    // engine.io-client-java#124, #125, #126: the Java client copied the query into a HashMap and
    // sent the parameters in hash order. JavaScript keeps the insertion order: the caller's
    // parameters as given, then EIO and transport, then what the transport adds (t, sid).
    @Test
    fun keepsTheOrderOfTheQueryParameters() =
        runTest {
            fun keys(url: String) = url.substringAfter('?').split('&').map { it.substringBefore('=') }
            val h = engineHarness()
            val engine = h.engine(uri = "http://localhost:3000/?z=1&a=2&m=3")
            h.settle()
            assertEquals("websocket", engine.transport!!.name)
            val requests = h.server.requests.toList()
            assertEquals(listOf("z", "a", "m", "EIO", "transport", "t"), keys(requests.first().url))
            // The handshake adds sid behind t, as `this.transport.query.sid = data.sid` does.
            val polls = requests.drop(1).filter { it.method != "UPGRADE" }
            assertTrue(polls.isNotEmpty())
            polls.forEach { assertEquals(listOf("z", "a", "m", "EIO", "transport", "t", "sid"), keys(it.url), it.url) }
            val upgrade = requests.single { it.method == "UPGRADE" }
            assertTrue(upgrade.url.startsWith("ws://localhost:3000/engine.io/?z=1&a=2&m=3&EIO=4&transport=websocket&sid="), upgrade.url)
            assertEquals(listOf("z", "a", "m", "EIO", "transport", "sid"), keys(upgrade.url))
            h.close()

            val option = engineHarness()
            option.engine(EngineOptions(transports = listOf("websocket"), query = mapOf("z" to "1", "a" to "2", "m" to "3")))
            option.settle()
            assertEquals("ws://localhost:3000/engine.io/?z=1&a=2&m=3&EIO=4&transport=websocket", option.server.requests.single().url)
            option.close()
        }

    @Test
    fun sendsTheClosePacketWhenClosingPolling() =
        runTest {
            val h = engineHarness()
            val engine = h.engine(EngineOptions(transports = listOf("polling")))
            h.settle()
            h.executor.execute { engine.close() }
            h.settle()
            assertEquals("forced close", h.all<EngineEvent.Close>().single().reason)
            assertEquals("1", h.server.requests.last { it.method == "POST" }.body)
            assertFalse(h.server.sessions.values.single().isOpen)
            h.close()
        }

    @Test
    fun theNetworkLostHookClosesWithTransportClose() =
        runTest {
            val h = engineHarness()
            val engine = h.engine()
            h.settle()
            h.executor.execute { engine.onNetworkLost() }
            h.settle()
            val close = h.all<EngineEvent.Close>().single()
            assertEquals("transport close", close.reason)
            assertEquals("network connection lost", (close.description as CloseDetails).description)
            h.close()
        }

    @Test
    fun usesTheTestTimeSourceByDefaultInTests() =
        runTest {
            // Guards the harness itself: the executor's clock is the virtual one.
            val h = engineHarness()
            val mark = h.executor.timeSource.markNow()
            advanceTimeBy(5.seconds)
            assertEquals(5.seconds, mark.elapsedNow())
            h.close()
        }
}

/** Answers every request with a fixed body, for handshake validation tests. */
private class FakeHandshakeClient(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val body: String,
) : EngineHttpClient {
    override fun execute(
        request: EngineHttpRequest,
        callback: EngineHttpCallback,
    ): Cancellable {
        scope.launch { callback.onResponse(EngineHttpResponse(200, body)) }
        return Cancellable.NONE
    }
}
