package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.seconds

/** Hostile or broken input and misconfiguration end in events, never in exceptions escaping the engine. */
class EngineRobustnessTest {
    // The name keeps the method name first, so the parity gate can match the JUnit report to the contract.
    @ParameterizedTest(name = "{displayName} [{index}] {0}")
    @ValueSource(
        strings = [
            "0not json",
            "0[]",
            "0{\"upgrades\":[],\"pingInterval\":1,\"pingTimeout\":1}",
            "0{\"sid\":\"a\",\"upgrades\":\"websocket\",\"pingInterval\":1,\"pingTimeout\":1}",
            "0{\"sid\":\"a\",\"upgrades\":[1],\"pingInterval\":1,\"pingTimeout\":1}",
            "0{\"sid\":\"a\",\"upgrades\":[],\"pingTimeout\":1}",
            "0{\"sid\":\"a\",\"upgrades\":[],\"pingInterval\":-1,\"pingTimeout\":1}",
            "0{\"sid\":\"a\",\"upgrades\":[],\"pingInterval\":\"1\",\"pingTimeout\":1}",
            "0{\"sid\":\"a\",\"upgrades\":[],\"pingInterval\":1,\"pingTimeout\":1,\"maxPayload\":\"big\"}",
            "0{\"sid\":\"a\",\"upgrades\":[],\"pingInterval\":1,\"pingTimeout\":1,\"maxPayload\":-5}",
            "0{\"sid\":\"a\",\"upgrades\":[],\"pingInterval\":600000,\"pingTimeout\":1}",
            "0",
        ],
    )
    fun aHostileHandshakeIsRejected(body: String) =
        runTest {
            val h = engineHarness()
            val server = RawHandshakeClient(backgroundScope, body)
            val options = EngineOptions(clients = EngineClients(server, null), handshakeLimits = HandshakeLimits(maxPingInterval = 60.seconds))
            val engine = EngineSocket("http://localhost", options, h.executor)
            engine.events.on(EngineEvent::class.java) { h.events.add(it) }
            h.executor.execute { engine.open() }
            h.settle()
            assertEquals(listOf("transport error"), h.all<EngineEvent.Close>().map { it.reason })
            assertTrue(h.all<EngineEvent.Open>().isEmpty())
            h.close()
        }

    @Test
    fun aRequestTheHttpStackCannotStartIsATransportError() =
        runTest {
            val h = engineHarness()
            val refusing =
                object : EngineHttpClient {
                    override fun execute(
                        request: EngineHttpRequest,
                        callback: EngineHttpCallback,
                    ): Cancellable = throw IllegalArgumentException("Unexpected char 0xe9 in header value")
                }
            val engine = EngineSocket("http://localhost", EngineOptions(clients = EngineClients(refusing, null)), h.executor)
            engine.events.on(EngineEvent::class.java) { h.events.add(it) }
            h.executor.execute { engine.open() }
            h.settle()
            assertEquals("xhr poll error", h.all<EngineEvent.Error>().single().error.message)
            assertEquals(listOf("transport error"), h.all<EngineEvent.Close>().map { it.reason })
            h.close()
        }

    @Test
    fun aRequestThatCannotStartAfterTheHandshakeClosesTheConnection() =
        runTest {
            val h = engineHarness()
            var requests = 0
            val handshakeThenRefuse =
                object : EngineHttpClient {
                    override fun execute(
                        request: EngineHttpRequest,
                        callback: EngineHttpCallback,
                    ): Cancellable {
                        if (requests++ > 0) throw IllegalArgumentException("Unexpected char 0xe9 in cookie value")
                        backgroundScope.launch {
                            callback.onResponse(
                                EngineHttpResponse(200, "0{\"sid\":\"s\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000}"),
                            )
                        }
                        return Cancellable.NONE
                    }
                }
            val engine = EngineSocket("http://localhost", EngineOptions(clients = EngineClients(handshakeThenRefuse, null)), h.executor)
            engine.events.on(EngineEvent::class.java) { h.events.add(it) }
            h.executor.execute { engine.open() }
            h.settle()
            assertEquals(1, h.all<EngineEvent.Open>().size)
            assertEquals(listOf("transport error"), h.all<EngineEvent.Close>().map { it.reason })
            assertEquals(EngineState.CLOSED, engine.readyState)
            h.close()
        }

    @Test
    fun aRejectedHandshakeWithTryAllTransportsClosesThePollingTransport() =
        runTest {
            val h = engineHarness()
            val polls = ArrayList<EngineHttpCallback>()
            val posts = ArrayList<String?>()
            val http =
                object : EngineHttpClient {
                    override fun execute(
                        request: EngineHttpRequest,
                        callback: EngineHttpCallback,
                    ): Cancellable {
                        if (request.method == "GET") polls.add(callback) else posts.add(request.body)
                        return Cancellable.NONE
                    }
                }
            val options = EngineOptions(tryAllTransports = true, clients = EngineClients(http, RecordingWebSocketClient()))
            val engine = EngineSocket("http://localhost", options, h.executor)
            h.executor.execute { engine.open() }
            h.settle()
            // The polling transport opens with this response, but the handshake has no sid.
            polls.single().onResponse(EngineHttpResponse(200, "0{}"))
            h.settle()
            assertEquals(1, polls.size, "the abandoned polling transport polled again")
            assertEquals(listOf<String?>("1"), posts)
            assertEquals("websocket", engine.transport!!.name)
            h.close()
        }

    @Test
    fun closingAConnectingWebSocketCancelsIt() =
        runTest {
            val h = engineHarness()
            val ws = RecordingWebSocketClient()
            val engine = EngineSocket("http://localhost", EngineOptions(transports = listOf("websocket"), clients = EngineClients(null, ws)), h.executor)
            h.executor.execute { engine.open() }
            h.settle()
            h.executor.execute { engine.close() }
            h.settle()
            assertEquals(listOf("cancel"), ws.calls)

            val open = RecordingWebSocketClient()
            val connected = EngineSocket("http://localhost", EngineOptions(transports = listOf("websocket"), clients = EngineClients(null, open)), h.executor)
            h.executor.execute { connected.open() }
            h.settle()
            open.listener!!.onOpen(emptyList())
            h.settle()
            h.executor.execute { connected.close() }
            h.settle()
            assertEquals(listOf("close 1000"), open.calls)
            h.close()
        }

    @Test
    fun anUnknownTransportNameIsAnErrorEventNotAnException() =
        runTest {
            val h = engineHarness()
            h.engine(EngineOptions(transports = listOf("carrier-pigeon")))
            h.settle()
            assertEquals("Unknown transport \"carrier-pigeon\"", h.all<EngineEvent.Error>().single().error.message)
            assertTrue(h.all<EngineEvent.Open>().isEmpty())
            h.close()
        }

    @Test
    fun aThrowingPacketObserverDoesNotBreakTheConnection() =
        runTest {
            val h = engineHarness()
            var calls = 0
            val engine =
                h.engine(
                    EngineOptions(
                        packetObserver = { _, _ ->
                            calls++
                            throw IllegalStateException("observer bug")
                        },
                    ),
                )
            h.settle()
            h.executor.execute { engine.send("still works") }
            h.settle()
            assertEquals(listOf("hi", "still works"), h.messages)
            assertTrue(calls > 2)
            assertTrue(h.all<EngineEvent.Close>().isEmpty())
            h.close()
        }

    @Test
    fun aProbeTransportThatCannotBeCreatedReportsUpgradeErrorAndKeepsPolling() =
        runTest {
            val h = engineHarness()
            val engine =
                h.engine(
                    EngineOptions(transportFactories = mapOf("websocket" to EngineTransport.Factory { throw IllegalStateException("no sockets today") })),
                )
            h.settle()
            val error = h.all<EngineEvent.UpgradeError>().single()
            assertEquals("websocket", error.transportName)
            assertEquals("probe error: no sockets today", error.error.message)
            assertEquals("polling", engine.transport!!.name)
            h.executor.execute { engine.send("over polling") }
            h.settle()
            assertEquals(listOf("hi", "over polling"), h.messages)
            h.close()
        }

    // As in JavaScript: the parser's error packet becomes Error("server error") with code "parser error".
    @Test
    fun anUndecodableWebSocketFrameClosesWithTransportError() =
        runTest {
            val h = engineHarness()
            val engine = h.engine(EngineOptions(transports = listOf("websocket")))
            h.settle()
            assertEquals("websocket", engine.transport!!.name)
            // "x" is not a packet type: it decodes to the parser's error packet.
            h.server.sessions.values.single().sendRawFrame("x")
            h.settle()
            val error = h.all<EngineEvent.Error>().single().error
            assertEquals("server error", error.message)
            assertEquals("parser error", (error as EngineServerException).code)
            assertEquals("transport error", h.all<EngineEvent.Close>().single().reason)
            h.close()
        }

    // JavaScript rejects an upgrade whose probe is not answered with "3probe" and keeps polling.
    @Test
    fun aWrongProbeAnswerRejectsTheUpgradeAndKeepsPolling() =
        runTest {
            val h = engineHarness()
            h.server.probeAnswer = "not the probe"
            val engine = h.engine()
            h.settle()
            val error = h.all<EngineEvent.UpgradeError>().single()
            assertEquals("probe error", error.error.message)
            assertEquals("websocket", error.transportName)
            assertEquals("polling", engine.transport!!.name)
            h.executor.execute { engine.send("still polling") }
            h.settle()
            assertEquals(listOf("hi", "still polling"), h.messages)
            h.close()
        }

    // A custom HTTP stack without WebSocket support: polling works, the upgrade is skipped with an error.
    @Test
    fun withoutAWebSocketClientTheEngineStaysOnPolling() =
        runTest {
            val h = engineHarness()
            val engine = EngineSocket("http://localhost", EngineOptions(clients = EngineClients(h.server, null)), h.executor)
            engine.events.on(EngineEvent::class.java) { h.events.add(it) }
            h.executor.execute { engine.open() }
            h.settle()
            assertTrue(h.all<EngineEvent.Open>().isNotEmpty())
            assertEquals("polling", engine.transport!!.name)
            assertTrue(h.all<EngineEvent.UpgradeError>().single().error.message!!.contains("needs an EngineWebSocketClient"))
            h.close()
        }

    @Test
    fun webSocketOnlyWithoutAWebSocketClientIsAnErrorEvent() =
        runTest {
            val h = engineHarness()
            val options = EngineOptions(transports = listOf("websocket"), clients = EngineClients(h.server, null))
            val engine = EngineSocket("http://localhost", options, h.executor)
            engine.events.on(EngineEvent::class.java) { h.events.add(it) }
            h.executor.execute { engine.open() }
            h.settle()
            assertTrue(h.all<EngineEvent.Error>().single().error.message!!.contains("needs an EngineWebSocketClient"))
            assertTrue(h.all<EngineEvent.Open>().isEmpty())
            h.close()
        }
}

/** A WebSocket client whose connections never open by themselves; records how they end. */
private class RecordingWebSocketClient : EngineWebSocketClient {
    var listener: EngineWebSocketListener? = null
    val calls = ArrayList<String>()

    override fun connect(
        request: EngineWebSocketRequest,
        listener: EngineWebSocketListener,
    ): EngineWebSocketConnection {
        this.listener = listener
        return object : EngineWebSocketConnection {
            override fun send(
                text: String,
                compress: Boolean,
            ) = true

            override fun send(
                bytes: ByteArray,
                compress: Boolean,
            ) = true

            override val queuedBytes: Long get() = 0

            override fun close(
                code: Int,
                reason: String?,
            ) {
                calls += "close $code"
            }

            override fun cancel() {
                calls += "cancel"
            }
        }
    }
}

/** Answers every request with [body], like a broken or hostile server. */
private class RawHandshakeClient(
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
