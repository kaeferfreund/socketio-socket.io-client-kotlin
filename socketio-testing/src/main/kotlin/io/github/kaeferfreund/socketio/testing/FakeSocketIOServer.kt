package io.github.kaeferfreund.socketio.testing

import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.parser.SocketIODecoder
import io.github.kaeferfreund.socketio.parser.SocketIOEncoder
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import io.github.kaeferfreund.socketio.parser.SocketIOParseException
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An in-memory Socket.IO 5 server on top of [FakeEngineServer], for fast,
 * deterministic tests of code that uses the client — including on virtual
 * time.
 *
 * It implements the server side of the protocol: namespaces with optional
 * middleware, acknowledgements in both directions, binary attachments,
 * server-initiated disconnects and connection state recovery (session ids,
 * offsets and replay of missed events).
 *
 * ```kotlin
 * val server = FakeSocketIOServer(backgroundScope)
 * server.namespace("/").onConnection { client -> client.on("hi") { _, _ -> client.emit("hi") } }
 * val manager = SocketManager("http://fake", SocketManagerOptions { clients = server.clients })
 * ```
 */
public class FakeSocketIOServer(
    scope: CoroutineScope,
    pingInterval: Duration = 25.seconds,
    pingTimeout: Duration = 20.seconds,
    maxPayload: Long = 1_000_000,
    upgrades: List<String> = listOf("websocket"),
    heartbeat: Boolean = true,
    /** Enable connection state recovery, as `connectionStateRecovery` on a real server. */
    public val recovery: Boolean = false,
) {
    /** The underlying engine server, for transport-level control. */
    public val engine: FakeEngineServer = FakeEngineServer(scope, pingInterval, pingTimeout, maxPayload, upgrades, heartbeat)

    /** Clients for `SocketManagerOptions.clients`. */
    public val clients: EngineClients get() = engine.clients

    private val namespaces = LinkedHashMap<String, Namespace>()
    private val encoder = SocketIOEncoder()
    private var nextSocketId = 0
    private var nextPid = 0
    private var nextOffset = 0

    /** Every packet received from any client, decoded, in order. */
    public val received: MutableList<SocketIOPacket> = ArrayList()

    /** Missed events by session id, for recovery. */
    private val recoverable = HashMap<String, RecoverableSession>()

    private class RecoverableSession(
        val namespace: String,
        val socketId: String,
        val missed: MutableList<Pair<String, SocketIOPacket>> = ArrayList(),
    )

    /** A namespace: connection handler and middleware. */
    public inner class Namespace internal constructor(
        public val name: String,
    ) {
        internal var connectionHandler: (Client) -> Unit = {}
        internal var middleware: (Handshake) -> Refusal? = { null }

        /** Connected clients. */
        public val clients: MutableList<Client> = ArrayList()

        /** Called for every connected client. */
        public fun onConnection(handler: (Client) -> Unit): Namespace {
            connectionHandler = handler
            return this
        }

        /** Runs before a client connects; return a [Refusal] to reject it with CONNECT_ERROR. */
        public fun use(middleware: (Handshake) -> Refusal?): Namespace {
            this.middleware = middleware
            return this
        }

        /** Emits to every connected client of this namespace. */
        public fun emit(
            event: String,
            vararg args: Any?,
        ) {
            val data = SocketIOValue.Array(listOf(SocketIOValue.Text(event)) + args.map { SocketIOValue.of(it) })
            for (client in clients.toList()) client.sendEvent(data)
            if (recovery) {
                for (session in recoverable.values) {
                    if (session.namespace == name) {
                        session.missed.add((nextOffset++).toString() to SocketIOPacket(SocketIOPacketType.EVENT, name, data))
                    }
                }
            }
        }
    }

    /** What a client sent in its CONNECT packet. */
    public class Handshake(
        public val namespace: String,
        /** The CONNECT payload (`socket.handshake.auth`). */
        public val auth: SocketIOValue.Object?,
        /** Query of the engine's handshake request. */
        public val query: Map<String, String>,
    )

    /** A middleware refusal: `next(new Error(message))` with optional `err.data`. */
    public class Refusal(
        public val message: String,
        public val data: Any? = null,
    )

    /** One connected namespace socket, server side. */
    public inner class Client internal constructor(
        public val session: FakeEngineServer.Session,
        public val namespace: Namespace,
        /** `socket.id` on the server. */
        public val id: String,
        public val handshake: Handshake,
        internal val pid: String?,
    ) {
        private val handlers = HashMap<String, (List<SocketIOValue>, ((List<Any?>) -> Unit)?) -> Unit>()
        private var nextAckId = 0L
        private val pendingAcks = HashMap<Long, (List<SocketIOValue>) -> Unit>()

        /** Events received from this client: name and arguments. */
        public val events: MutableList<Pair<String, List<SocketIOValue>>> = ArrayList()

        /** Whether the client is connected to the namespace. */
        public var connected: Boolean = true
            internal set

        /** Handles [event]; `ack` is non-null when the client asked for an acknowledgement. */
        public fun on(
            event: String,
            handler: (args: List<SocketIOValue>, ack: ((List<Any?>) -> Unit)?) -> Unit,
        ) {
            handlers[event] = handler
        }

        /** Emits [event] to this client. */
        public fun emit(
            event: String,
            vararg args: Any?,
        ) {
            sendEvent(SocketIOValue.Array(listOf(SocketIOValue.Text(event)) + args.map { SocketIOValue.of(it) }))
        }

        /** Emits [event] and waits for the client's acknowledgement. */
        public fun emitWithAck(
            event: String,
            vararg args: Any?,
            onAck: (List<SocketIOValue>) -> Unit,
        ) {
            val id = nextAckId++
            pendingAcks[id] = onAck
            val data = SocketIOValue.Array(listOf(SocketIOValue.Text(event)) + args.map { SocketIOValue.of(it) })
            write(SocketIOPacket(SocketIOPacketType.EVENT, namespace.name, data, id))
        }

        internal fun sendEvent(data: SocketIOValue.Array) {
            val payload =
                if (recovery && pid != null) {
                    SocketIOValue.Array(data.items + SocketIOValue.Text((nextOffset++).toString()))
                } else {
                    data
                }
            write(SocketIOPacket(SocketIOPacketType.EVENT, namespace.name, payload))
        }

        /** Sends any packet to this client. */
        public fun write(packet: SocketIOPacket) {
            for (frame in encoder.encode(packet)) {
                when (frame) {
                    is EngineIOData.Text -> session.send(frame.value)
                    is EngineIOData.Binary -> session.send(frame.bytes)
                }
            }
        }

        /** `socket.disconnect()` on the server: a DISCONNECT packet, optionally closing the connection. */
        public fun disconnect(closeConnection: Boolean = false) {
            write(SocketIOPacket(SocketIOPacketType.DISCONNECT, namespace.name))
            connected = false
            namespace.clients.remove(this)
            if (closeConnection) session.close()
        }

        internal fun onEvent(packet: SocketIOPacket) {
            val items = (packet.data as? SocketIOValue.Array)?.items ?: return
            val name = items.firstOrNull()?.string ?: items.firstOrNull()?.toString() ?: return
            val args = items.drop(1)
            events.add(name to args)
            val ackId = packet.id
            val ack: ((List<Any?>) -> Unit)? =
                ackId?.let { id ->
                    { values: List<Any?> ->
                        write(SocketIOPacket(SocketIOPacketType.ACK, namespace.name, SocketIOValue.Array(values.map { SocketIOValue.of(it) }), id))
                    }
                }
            handlers[name]?.invoke(args, ack)
        }

        internal fun onAck(packet: SocketIOPacket) {
            val id = packet.id ?: return
            pendingAcks.remove(id)?.invoke((packet.data as? SocketIOValue.Array)?.items ?: emptyList())
        }
    }

    /** The namespace [name], created on first use. */
    public fun namespace(name: String = "/"): Namespace = namespaces.getOrPut(name) { Namespace(name) }

    init {
        namespace("/")
        engine.onConnection = { session -> attach(session) }
    }

    private fun attach(session: FakeEngineServer.Session) {
        val decoder = SocketIODecoder()
        val clients = HashMap<String, Client>()
        val query = session.handshakeRequest.query
        session.onMessage = { data ->
            val packet =
                try {
                    decoder.add(data)
                } catch (e: SocketIOParseException) {
                    session.close()
                    null
                }
            if (packet != null) {
                received.add(packet)
                when (packet.type) {
                    SocketIOPacketType.CONNECT -> onConnect(session, packet, clients, query)

                    SocketIOPacketType.EVENT, SocketIOPacketType.BINARY_EVENT -> clients[packet.nsp]?.onEvent(packet)

                    SocketIOPacketType.ACK, SocketIOPacketType.BINARY_ACK -> clients[packet.nsp]?.onAck(packet)

                    SocketIOPacketType.DISCONNECT -> {
                        clients.remove(packet.nsp)?.let {
                            it.connected = false
                            it.namespace.clients.remove(it)
                        }
                    }

                    else -> Unit
                }
            }
        }
        session.onClose = {
            for (client in clients.values) {
                client.connected = false
                client.namespace.clients.remove(client)
                if (recovery && client.pid != null) recoverable[client.pid] = RecoverableSession(client.namespace.name, client.id)
            }
            clients.clear()
        }
    }

    private fun onConnect(
        session: FakeEngineServer.Session,
        packet: SocketIOPacket,
        clients: MutableMap<String, Client>,
        query: Map<String, String>,
    ) {
        val namespace = namespaces[packet.nsp]
        val writeTo = { p: SocketIOPacket ->
            for (frame in encoder.encode(p)) if (frame is EngineIOData.Text) session.send(frame.value)
        }
        if (namespace == null) {
            writeTo(SocketIOPacket(SocketIOPacketType.CONNECT_ERROR, packet.nsp, SocketIOValue.objectOf("message" to "Invalid namespace")))
            return
        }
        val auth = packet.data as? SocketIOValue.Object
        val handshake = Handshake(packet.nsp, auth, query)
        namespace.middleware(handshake)?.let { refusal ->
            val payload = SocketIOValue.objectOf("message" to refusal.message, "data" to refusal.data)
            writeTo(SocketIOPacket(SocketIOPacketType.CONNECT_ERROR, packet.nsp, payload))
            return
        }
        val requestedPid = auth?.get("pid")?.string
        val restored = if (recovery && requestedPid != null) recoverable.remove(requestedPid) else null
        val id = restored?.socketId ?: "socket${nextSocketId++}"
        val pid = if (recovery) restored?.let { requestedPid } ?: "pid${nextPid++}" else null
        val client = Client(session, namespace, id, handshake, pid)
        clients[packet.nsp] = client
        namespace.clients.add(client)
        val data = if (pid != null) SocketIOValue.objectOf("sid" to id, "pid" to pid) else SocketIOValue.objectOf("sid" to id)
        writeTo(SocketIOPacket(SocketIOPacketType.CONNECT, packet.nsp, data))
        if (restored != null) {
            val offset = auth?.get("offset")?.string
            val replay =
                restored.missed.dropWhile { (o, _) -> offset != null && o.toLongOrNull()?.let { it <= (offset.toLongOrNull() ?: -1) } == true }
            for ((o, missed) in replay) {
                val items = (missed.data as SocketIOValue.Array).items + SocketIOValue.Text(o)
                client.write(SocketIOPacket(SocketIOPacketType.EVENT, missed.nsp, SocketIOValue.Array(items)))
            }
        }
        namespace.connectionHandler(client)
    }
}
