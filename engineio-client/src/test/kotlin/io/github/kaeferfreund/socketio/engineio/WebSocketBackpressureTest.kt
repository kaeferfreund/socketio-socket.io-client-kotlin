package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * socket.io-client-java#773/#726: OkHttp closes a WebSocket whose outgoing queue would exceed
 * 16 MiB, which browsers and Node never do. The transport hands the connection only what fits and
 * reports `drain` (writable again) once everything is handed over, as JavaScript does after `ws.send`.
 */
class WebSocketBackpressureTest {
    private class QueueingConnection(
        override val maxQueuedBytes: Long,
    ) : EngineWebSocketConnection {
        val sent = ArrayList<String>()
        var queued = 0L

        override fun send(
            text: String,
            compress: Boolean,
        ): Boolean {
            check(queued + text.length <= maxQueuedBytes) { "queue overfilled: the real client would close the socket" }
            sent += text
            queued += text.length
            return true
        }

        override fun send(
            bytes: ByteArray,
            compress: Boolean,
        ): Boolean = error("binary not used")

        override val queuedBytes: Long get() = queued

        override fun close(
            code: Int,
            reason: String?,
        ) = Unit

        override fun cancel() = Unit
    }

    private class CapturingClient(
        private val connection: EngineWebSocketConnection,
    ) : EngineWebSocketClient {
        var listener: EngineWebSocketListener? = null

        override fun connect(
            request: EngineWebSocketRequest,
            listener: EngineWebSocketListener,
        ): EngineWebSocketConnection {
            this.listener = listener
            return connection
        }
    }

    private class Harness(
        val transport: WebSocketTransport,
        val connection: QueueingConnection,
        val executor: ProtocolExecutor,
        val events: MutableList<TransportEvent>,
    )

    private fun TestScope.open(maxQueuedBytes: Long): Harness {
        val connection = QueueingConnection(maxQueuedBytes)
        val client = CapturingClient(connection)
        val executor = ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
        val transport = WebSocketTransport(options(executor, client))
        val events = ArrayList<TransportEvent>()
        transport.events.on(TransportEvent::class.java) { events += it }
        executor.execute { transport.open() }
        runCurrent()
        client.listener!!.onOpen(emptyList())
        runCurrent()
        return Harness(transport, connection, executor, events)
    }

    private fun message(text: String) = EngineIOPacket(EngineIOPacketType.MESSAGE, text)

    @Test
    fun packetsWaitWhileTheConnectionQueueIsFullAndDrainComesLast() =
        runTest {
            val h = open(maxQueuedBytes = 100)
            val payloads = List(3) { index -> "$index" + "x".repeat(49) }
            h.executor.execute { h.transport.send(payloads.map(::message)) }
            runCurrent()
            // "4" + 50 characters is 51 bytes: the second packet would overfill the 100-byte queue.
            assertEquals(listOf("4" + payloads[0]), h.connection.sent)
            assertTrue(h.events.none { it is TransportEvent.Drain })
            h.connection.queued = 0
            advanceTimeBy(20.milliseconds)
            runCurrent()
            assertEquals(2, h.connection.sent.size)
            assertTrue(h.events.none { it is TransportEvent.Drain })
            h.connection.queued = 0
            advanceTimeBy(20.milliseconds)
            runCurrent()
            assertEquals(payloads.map { "4$it" }, h.connection.sent)
            assertEquals(1, h.events.count { it is TransportEvent.Drain })
            h.executor.shutdown()
        }

    @Test
    fun withoutAQueueLimitEverythingIsSentAtOnceAndDrainFollows() =
        runTest {
            val h = open(maxQueuedBytes = Long.MAX_VALUE)
            h.executor.execute { h.transport.send(List(3) { message("m$it") }) }
            runCurrent()
            assertEquals(listOf("4m0", "4m1", "4m2"), h.connection.sent)
            assertEquals(1, h.events.count { it is TransportEvent.Drain })
            h.executor.shutdown()
        }

    @Test
    fun aMessageLargerThanTheConnectionLimitIsAClearTransportError() =
        runTest {
            val h = open(maxQueuedBytes = 10)
            h.executor.execute { h.transport.send(listOf(message("far too long for the queue"))) }
            runCurrent()
            val error = h.events.filterIsInstance<TransportEvent.Error>().single().error
            assertEquals("websocket error", error.message)
            assertTrue(error.cause!!.message!!.contains("exceeds"), error.cause!!.message)
            assertTrue(h.connection.sent.isEmpty())
            h.executor.shutdown()
        }

    private fun options(
        executor: ProtocolExecutor,
        ws: EngineWebSocketClient,
    ) = TransportOptions(
        hostname = "localhost",
        secure = false,
        port = "80",
        path = "/engine.io/",
        query = mapOf("EIO" to "4"),
        timestampRequests = null,
        timestampParam = "t",
        forceBase64 = false,
        extraHeaders = emptyMap(),
        requestTimeout = null,
        withCredentials = false,
        protocols = emptyList(),
        perMessageDeflateThreshold = null,
        maxPollingResponseBytes = Long.MAX_VALUE,
        executor = executor,
        cookieJar = null,
        httpClient =
        object : EngineHttpClient {
            override fun execute(
                request: EngineHttpRequest,
                callback: EngineHttpCallback,
            ): Cancellable = Cancellable.NONE
        },
        webSocketClient = ws,
        logger = SocketLogger.NONE,
    )
}
