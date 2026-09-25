package io.github.kaeferfreund.socketio.testing

import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpClient
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import io.github.kaeferfreund.socketio.engineio.EngineUri
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketClient
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketConnection
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketListener
import io.github.kaeferfreund.socketio.engineio.EngineWebSocketRequest
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An in-memory Engine.IO 4 server that implements both [EngineHttpClient]
 * and [EngineWebSocketClient], so a real engine with its real polling and
 * WebSocket transports can be driven without a network.
 *
 * Every response is delivered asynchronously on [scope], never inside the
 * caller's stack, just as a network would. With a `TestScope` everything —
 * long polls, heartbeats, delays — runs on virtual time.
 *
 * The server behaves like the `engine.io` package: polling handshake,
 * one long poll per session (an overlapping GET is a protocol error),
 * POST batches, the `2probe`/`3probe` upgrade with a `noop` to release the
 * pending poll, direct WebSocket connections, server pings and
 * `pingTimeout`, `maxPayload` on POST bodies and the documented error bodies.
 *
 * Not thread-safe: use it from one test dispatcher.
 */
public class FakeEngineServer(
    private val scope: CoroutineScope,
    public val pingInterval: Duration = 25.seconds,
    public val pingTimeout: Duration = 20.seconds,
    public val maxPayload: Long = 1_000_000,
    /** Upgrades offered in the handshake. */
    public val upgrades: List<String> = listOf("websocket"),
    /** Send pings and close sessions whose pong is late. */
    public val heartbeat: Boolean = true,
) : EngineHttpClient,
    EngineWebSocketClient {
    /** The clients to pass to `EngineOptions.clients`. */
    public val clients: EngineClients get() = EngineClients(this, this)

    /** One recorded HTTP request or WebSocket handshake. */
    public class RecordedRequest(
        public val method: String,
        public val url: String,
        public val headers: List<Pair<String, String>>,
        public val body: String?,
    ) {
        /** Decoded query parameters. */
        public val query: Map<String, String> get() = EngineUri.decodeQuery(url.substringAfter('?', ""))

        /** First value of header [name]. */
        public fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        override fun toString(): String = "$method $url"
    }

    /** Every request, in arrival order. */
    public val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    /** Sessions by id, including closed ones. */
    public val sessions: MutableMap<String, Session> = LinkedHashMap()

    /** Called for every new session, before its handshake is answered. */
    public var onConnection: (Session) -> Unit = {}

    /** Returns an HTTP status to refuse a request with, or `null` to accept it (`allowRequest`). */
    public var allowRequest: (RecordedRequest) -> Int? = { null }

    /** Extra response headers, for example `Set-Cookie`, on every handshake response. */
    public var handshakeHeaders: List<Pair<String, String>> = emptyList()

    /** Delay before every HTTP response and WebSocket event. */
    public var latency: Duration = Duration.ZERO

    /** Answer the pending long poll with a `noop` when a probe arrives, as engine.io does. */
    public var releasePollOnProbe: Boolean = true

    /** The data of the pong that answers the `2probe` ping; anything but `"probe"` makes the client reject the upgrade. */
    public var probeAnswer: String = "probe"

    /** Holds requests matching the predicate forever (for timeout tests). */
    public var stall: (RecordedRequest) -> Boolean = { false }

    private var nextId = 0

    /** A client connection. */
    public inner class Session internal constructor(
        public val id: String,
        /** The request that opened the session (polling handshake or WebSocket upgrade). */
        public val handshakeRequest: RecordedRequest,
    ) {
        /** `"polling"` or `"websocket"`. */
        public var transport: String = "polling"
            internal set

        /** Whether the session is still open. */
        public var isOpen: Boolean = true
            private set

        /** Every packet received from the client, including pongs and upgrade packets. */
        public val received: MutableList<EngineIOPacket> = ArrayList()

        /** Called for every `message` packet. */
        public var onMessage: (EngineIOData) -> Unit = {}

        /** Called once when the session closes, with the engine.io server reason. */
        public var onClose: (String) -> Unit = {}

        internal val outbox = ArrayList<EngineIOPacket>()
        internal var pendingPoll: EngineHttpCallback? = null
        internal var webSocket: FakeWebSocket? = null
        internal var probe: FakeWebSocket? = null
        private var heartbeatJob: Job? = null
        private var pongDeadline: Job? = null

        /** Messages received from the client, in order. */
        public val messages: List<EngineIOData>
            get() = received.filter { it.type == EngineIOPacketType.MESSAGE }.mapNotNull { it.data }

        /** Sends a text message. */
        public fun send(text: String): Unit = sendPacket(EngineIOPacket(EngineIOPacketType.MESSAGE, text))

        /** Sends a binary message. */
        public fun send(bytes: ByteArray): Unit = sendPacket(EngineIOPacket(EngineIOPacketType.MESSAGE, EngineIOData.Binary(bytes)))

        /** Sends any packet over the current transport. */
        public fun sendPacket(packet: EngineIOPacket) {
            // Like engine.io, nothing is sent once a close has been requested.
            if (!isOpen || closeAfterPoll != null) return
            val ws = webSocket
            if (ws != null) {
                ws.deliverPacket(packet)
            } else {
                outbox.add(packet)
                flushPoll()
            }
        }

        /**
         * Sends [text] as one WebSocket frame without encoding it, for hostile-input tests
         * (for example a frame that is not an Engine.IO packet). Requires the WebSocket transport.
         */
        public fun sendRawFrame(text: String) {
            val ws = checkNotNull(webSocket) { "sendRawFrame needs the WebSocket transport" }
            ws.deliverRaw(text)
        }

        /** Answers the outstanding long poll with an HTTP error, as a failing proxy or server would. */
        public fun failPendingPoll(
            status: Int,
            body: String = "",
        ) {
            val callback = pendingPoll ?: return
            pendingPoll = null
            respond(callback, EngineHttpResponse(status, body))
        }

        /** `true` while a long poll is being held. */
        public val hasPendingPoll: Boolean get() = pendingPoll != null

        /** Sends a server ping now. */
        public fun ping() {
            sendPacket(EngineIOPacket(EngineIOPacketType.PING))
            pongDeadline?.cancel()
            pongDeadline =
                scope.launch {
                    delay(pingTimeout)
                    close("ping timeout", sendClosePacket = false)
                }
        }

        /** Closes the session like `socket.close()` on the server: a `close` packet, then the transport ends. */
        public fun close(): Unit = close("forced close", sendClosePacket = true)

        /** Drops the underlying transport without a close packet (a network failure). */
        public fun kill(): Unit = close("transport close", sendClosePacket = false)

        internal fun startHeartbeat() {
            if (!heartbeat) return
            heartbeatJob =
                scope.launch {
                    while (true) {
                        delay(pingInterval)
                        if (!isOpen) return@launch
                        ping()
                    }
                }
        }

        internal fun onPacket(packet: EngineIOPacket) {
            if (!isOpen) return
            received.add(packet)
            when (packet.type) {
                EngineIOPacketType.PONG -> {
                    pongDeadline?.cancel()
                    pongDeadline = null
                }

                EngineIOPacketType.MESSAGE -> packet.data?.let(onMessage)

                EngineIOPacketType.CLOSE -> close("transport close", sendClosePacket = false)

                else -> Unit
            }
        }

        internal fun flushPoll() {
            val callback = pendingPoll ?: return
            if (outbox.isEmpty()) return
            pendingPoll = null
            val body = EngineIOParser.encodePayload(outbox.toList())
            outbox.clear()
            respond(callback, EngineHttpResponse(200, body))
            closeAfterPoll?.let { finishClose(it, sendClosePacket = true) }
        }

        /** Set while a polling session waits for the next poll to carry its close packet. */
        private var closeAfterPoll: String? = null

        internal fun close(
            reason: String,
            sendClosePacket: Boolean,
        ) {
            if (!isOpen) return
            if (sendClosePacket) {
                val ws = webSocket
                if (ws != null) {
                    ws.deliverPacket(EngineIOPacket(EngineIOPacketType.CLOSE))
                } else {
                    outbox.add(EngineIOPacket(EngineIOPacketType.CLOSE))
                    if (pendingPoll == null) {
                        // engine.io (`shouldClose`) answers the next poll with the queued packets and the
                        // close packet, and only then closes: nothing written before the close is lost.
                        if (closeAfterPoll == null) {
                            closeAfterPoll = reason
                            heartbeatJob?.cancel()
                            pongDeadline?.cancel()
                        }
                        return
                    }
                    flushPoll()
                }
            }
            finishClose(reason, sendClosePacket)
        }

        private fun finishClose(
            reason: String,
            sendClosePacket: Boolean,
        ) {
            closeAfterPoll = null
            isOpen = false
            heartbeatJob?.cancel()
            pongDeadline?.cancel()
            webSocket?.serverClose(if (sendClosePacket) 1000 else 1006, "")
            probe?.serverClose(1006, "")
            pendingPoll?.let { respond(it, EngineHttpResponse(400, "{\"code\":1,\"message\":\"Session ID unknown\"}")) }
            pendingPoll = null
            onClose(reason)
        }
    }

    private fun newSession(
        transport: String,
        request: RecordedRequest,
    ): Session {
        val session = Session("sid${nextId++}", request)
        session.transport = transport
        sessions[session.id] = session
        return session
    }

    private fun handshakeText(session: Session): String =
        "0{\"sid\":\"${session.id}\",\"upgrades\":${upgradesJson(session.transport)},\"pingInterval\":${pingInterval.inWholeMilliseconds}," +
            "\"pingTimeout\":${pingTimeout.inWholeMilliseconds},\"maxPayload\":$maxPayload}"

    private fun upgradesJson(transport: String): String =
        if (transport == "polling") upgrades.joinToString(",", "[", "]") { "\"$it\"" } else "[]"

    private fun respond(
        callback: EngineHttpCallback,
        response: EngineHttpResponse,
    ) {
        scope.launch {
            if (latency.isPositive()) delay(latency)
            callback.onResponse(response)
        }
    }

    override fun execute(
        request: EngineHttpRequest,
        callback: EngineHttpCallback,
    ): Cancellable {
        val recorded = RecordedRequest(request.method, request.url, request.headers, request.body)
        requests.add(recorded)
        var cancelled = false
        val guarded =
            object : EngineHttpCallback {
                override fun onResponse(response: EngineHttpResponse) {
                    if (!cancelled) callback.onResponse(response)
                }

                override fun onFailure(error: Throwable) {
                    if (!cancelled) callback.onFailure(error)
                }
            }
        val handle = Cancellable { cancelled = true }
        if (stall(recorded)) return handle
        allowRequest(recorded)?.let { status ->
            respond(guarded, EngineHttpResponse(status, "{\"code\":4,\"message\":\"Forbidden\"}"))
            return handle
        }
        val sid = recorded.query["sid"]
        if (sid == null) {
            if (request.method != "GET") {
                respond(guarded, EngineHttpResponse(400, "{\"code\":2,\"message\":\"Bad handshake method\"}"))
                return handle
            }
            val session = newSession("polling", recorded)
            respondHandshake(guarded, session)
            onConnection(session)
            session.startHeartbeat()
            return handle
        }
        val session = sessions[sid]
        if (session == null || !session.isOpen) {
            respond(guarded, EngineHttpResponse(400, "{\"code\":1,\"message\":\"Session ID unknown\"}"))
            return handle
        }
        if (request.method == "GET") {
            if (session.pendingPoll != null) {
                // engine.io closes the session on overlapping polls.
                respond(guarded, EngineHttpResponse(400, "{\"code\":3,\"message\":\"Bad request\"}"))
                session.close("transport error", sendClosePacket = false)
                return handle
            }
            session.pendingPoll = guarded
            session.flushPoll()
            return Cancellable {
                cancelled = true
                if (session.pendingPoll === guarded) session.pendingPoll = null
            }
        }
        val body = request.body.orEmpty()
        if (EngineUri.utf8Length(body) > maxPayload) {
            respond(guarded, EngineHttpResponse(413, ""))
            session.close("transport error", sendClosePacket = false)
            return handle
        }
        respond(guarded, EngineHttpResponse(200, "ok"))
        scope.launch {
            if (latency.isPositive()) delay(latency)
            for (packet in EngineIOParser.decodePayload(body)) session.onPacket(packet)
        }
        return handle
    }

    private fun respondHandshake(
        callback: EngineHttpCallback,
        session: Session,
    ) {
        scope.launch {
            if (latency.isPositive()) delay(latency)
            callback.onResponse(EngineHttpResponse(200, handshakeText(session), handshakeHeaders))
        }
    }

    override fun connect(
        request: EngineWebSocketRequest,
        listener: EngineWebSocketListener,
    ): EngineWebSocketConnection {
        val recorded = RecordedRequest("UPGRADE", request.url, request.headers, null)
        requests.add(recorded)
        val socket = FakeWebSocket(listener)
        scope.launch {
            if (latency.isPositive()) delay(latency)
            if (stall(recorded)) return@launch
            allowRequest(recorded)?.let { status ->
                socket.fail(IllegalStateException("Expected HTTP 101 but was $status"), status)
                return@launch
            }
            val sid = recorded.query["sid"]
            if (sid == null) {
                val session = newSession("websocket", recorded)
                session.webSocket = socket
                socket.session = session
                socket.open(handshakeHeaders)
                socket.deliverPacket(EngineIOPacket(EngineIOPacketType.OPEN, handshakeText(session).substring(1)))
                onConnection(session)
                session.startHeartbeat()
            } else {
                val session = sessions[sid]
                if (session == null || !session.isOpen) {
                    socket.fail(IllegalStateException("Expected HTTP 101 but was 400"), 400)
                    return@launch
                }
                socket.session = session
                socket.probing = true
                session.probe = socket
                socket.open(emptyList())
            }
        }
        return socket
    }

    /** The server side of one fake WebSocket. */
    public inner class FakeWebSocket internal constructor(
        private val listener: EngineWebSocketListener,
    ) : EngineWebSocketConnection {
        internal var session: Session? = null
        internal var probing = false
        private var open = false

        /** No more frames are accepted in either direction. */
        private var closed = false

        /** The client closed or the connection failed: frames still in flight are lost. */
        private var dropInFlight = false

        /** Frames the client sent, in order. */
        public val frames: MutableList<EngineIOData> = ArrayList()

        internal fun open(headers: List<Pair<String, String>>) {
            open = true
            listener.onOpen(headers)
        }

        internal fun fail(
            error: Throwable,
            status: Int?,
        ) {
            closed = true
            dropInFlight = true
            listener.onFailure(error, status)
        }

        // A frame sent before the server closes still arrives before the close, as over TCP.
        internal fun deliverPacket(packet: EngineIOPacket) {
            if (closed) return
            val data = EngineIOParser.encodePacket(packet, supportsBinary = true)
            scope.launch {
                if (latency.isPositive()) delay(latency)
                if (dropInFlight) return@launch
                when (data) {
                    is EngineIOData.Text -> listener.onMessage(data.value)
                    is EngineIOData.Binary -> listener.onMessage(data.bytes)
                }
            }
        }

        internal fun deliverRaw(text: String) {
            if (closed) return
            scope.launch {
                if (latency.isPositive()) delay(latency)
                if (!dropInFlight) listener.onMessage(text)
            }
        }

        internal fun serverClose(
            code: Int,
            reason: String,
        ) {
            if (closed) return
            closed = true
            scope.launch {
                if (latency.isPositive()) delay(latency)
                if (code == 1006) listener.onFailure(java.io.EOFException("connection reset"), null) else listener.onClosed(code, reason)
            }
        }

        private fun receive(data: EngineIOData) {
            if (closed || !open) return
            frames.add(data)
            val size =
                when (data) {
                    is EngineIOData.Text -> EngineUri.utf8Length(data.value)
                    is EngineIOData.Binary -> data.size.toLong()
                }
            if (size > maxPayload) {
                // The ws server closes with 1009 "Message Too Big".
                closed = true
                val session = session
                scope.launch {
                    if (latency.isPositive()) delay(latency)
                    listener.onClosed(1009, "")
                    if (session != null && session.webSocket === this@FakeWebSocket) session.close("transport error", sendClosePacket = false)
                }
                return
            }
            val packet = EngineIOParser.decodePacket(data)
            val session = session ?: return
            // Frames written before a close are still processed, as TCP delivers them in order.
            scope.launch {
                if (latency.isPositive()) delay(latency)
                if (probing) {
                    when {
                        packet.type == EngineIOPacketType.PING && packet.text == "probe" -> {
                            deliverPacket(EngineIOPacket(EngineIOPacketType.PONG, probeAnswer))
                            // Release the pending long poll so the client can pause polling.
                            if (releasePollOnProbe) session.sendPacket(EngineIOPacket(EngineIOPacketType.NOOP))
                        }

                        packet.type == EngineIOPacketType.UPGRADE -> {
                            probing = false
                            session.received.add(packet)
                            session.probe = null
                            session.webSocket = this@FakeWebSocket
                            session.transport = "websocket"
                            session.outbox.forEach(::deliverPacket)
                            session.outbox.clear()
                        }

                        else -> session.close("transport error", sendClosePacket = false)
                    }
                } else {
                    session.onPacket(packet)
                }
            }
        }

        override fun send(
            text: String,
            compress: Boolean,
        ): Boolean {
            if (closed) return false
            receive(EngineIOData.Text(text))
            return true
        }

        override fun send(
            bytes: ByteArray,
            compress: Boolean,
        ): Boolean {
            if (closed) return false
            receive(EngineIOData.Binary(bytes))
            return true
        }

        override val queuedBytes: Long get() = 0

        override fun close(
            code: Int,
            reason: String?,
        ) {
            if (closed) return
            closed = true
            dropInFlight = true
            val session = session
            scope.launch {
                if (latency.isPositive()) delay(latency)
                listener.onClosed(code, reason.orEmpty())
                if (session != null && session.webSocket === this@FakeWebSocket) session.close("transport close", sendClosePacket = false)
            }
        }

        override fun cancel() {
            if (closed) return
            closed = true
            dropInFlight = true
            val session = session
            if (session != null && session.webSocket === this) session.close("transport close", sendClosePacket = false)
        }
    }
}
