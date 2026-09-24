package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.EngineEvent
import io.github.kaeferfreund.socketio.engineio.EngineIOException
import io.github.kaeferfreund.socketio.engineio.EngineSocket
import io.github.kaeferfreund.socketio.engineio.EventEmitter
import io.github.kaeferfreund.socketio.engineio.InternalSocketIOApi
import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.engineio.ProtocolExecutor
import io.github.kaeferfreund.socketio.engineio.on
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketOptions
import io.github.kaeferfreund.socketio.parser.SocketIODecoder
import io.github.kaeferfreund.socketio.parser.SocketIOEncoder
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import io.github.kaeferfreund.socketio.parser.SocketIOParseException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * One connection to a Socket.IO server, shared by any number of namespace
 * [Socket]s: a port of `Manager` in `socket.io-client`.
 *
 * ```kotlin
 * val manager = SocketManager("https://example.com", SocketManagerOptions { reconnectionDelayMax = 10.seconds })
 * val socket = manager.socket("/chat")
 * ```
 *
 * Every method may be called from any thread. The protocol itself runs on a
 * private serial [ProtocolExecutor]; listener callbacks run there too unless
 * a [SocketManagerOptions.callbackDispatcher] is configured.
 *
 * With `autoConnect` (the default) the connection opens right away, as
 * `new Manager(uri)` does in JavaScript.
 */
public class SocketManager(
    /** The server URL; only scheme, host, port and query are used, the path comes from [SocketManagerOptions.Builder.path]. */
    public val uri: String,
    public val options: SocketManagerOptions = SocketManagerOptions.DEFAULT,
) : AutoCloseable {
    /** The serial executor owning all protocol state of this manager and its sockets. */
    @InternalSocketIOApi
    public val executor: ProtocolExecutor = ProtocolExecutor(options.dispatcher, options.timeSource)

    // ---- Protocol state: only touched on the executor. ------------------------------------------

    internal var engine: EngineSocket? = null
        private set
    internal var readyState: ManagerState = ManagerState.CLOSED
        private set
    internal var reconnecting: Boolean = false
        private set
    private var skipReconnect = false
    private val subs = ArrayList<Cancellable>()
    private val backoff =
        Backoff(options.reconnectionDelay, options.reconnectionDelayMax, options.randomizationFactor, options.random)
    private val encoder = SocketIOEncoder()
    private val decoder = SocketIODecoder(options.parserOptions)
    private var reconstructionTimer: Cancellable? = null
    private var networkAvailable = true
    private var reconnectTimer: Cancellable? = null
    private var reconnectWaitingForNetwork = false

    /** Internal fan-out to sockets, synchronous on the executor. */
    internal val internalEvents = EventEmitter<ManagerEvent>()

    private val nsps = ConcurrentHashMap<String, Socket>()

    // ---- Runtime-configurable options (JavaScript setters). -------------------------------------

    @Volatile private var reconnectionEnabled = options.reconnection

    @Volatile private var reconnectionAttemptsLimit = options.reconnectionAttempts

    @Volatile private var connectTimeout: Duration? = options.timeout

    // ---- Published views. -----------------------------------------------------------------------

    private val eventFlow = MutableSharedFlow<ManagerEvent>(extraBufferCapacity = Int.MAX_VALUE)

    /** Every manager event, for coroutine consumers. Hot: only events after collection starts are seen. */
    public val events: SharedFlow<ManagerEvent> = eventFlow.asSharedFlow()

    private val transportFlow = MutableStateFlow<String?>(null)

    /** Name of the active transport (`"polling"`, `"websocket"`) or `null` while not connected. */
    public val transportName: StateFlow<String?> = transportFlow.asStateFlow()

    private val listeners = CallbackListeners<ManagerEvent>()

    init {
        if (options.autoConnect) open()
    }

    /** Whether automatic reconnection is enabled (`reconnection()`). */
    public var reconnection: Boolean
        get() = reconnectionEnabled
        set(value) {
            reconnectionEnabled = value
            executor.execute { if (!value) skipReconnect = true }
        }

    /** Maximum reconnection attempts (`reconnectionAttempts()`). */
    public var reconnectionAttempts: Int
        get() = reconnectionAttemptsLimit
        set(value) {
            require(value >= 0) { "reconnectionAttempts must not be negative" }
            reconnectionAttemptsLimit = value
        }

    /** Initial reconnection delay (`reconnectionDelay()`). */
    public var reconnectionDelay: Duration
        get() = backoffMin
        set(value) {
            backoffMin = value
            executor.execute { backoff.min = value }
        }

    @Volatile private var backoffMin: Duration = options.reconnectionDelay

    @Volatile private var backoffMax: Duration = options.reconnectionDelayMax

    @Volatile private var backoffJitter: Double = options.randomizationFactor

    /** Maximum reconnection delay (`reconnectionDelayMax()`). */
    public var reconnectionDelayMax: Duration
        get() = backoffMax
        set(value) {
            backoffMax = value
            executor.execute { backoff.max = value }
        }

    /** Jitter factor (`randomizationFactor()`). */
    public var randomizationFactor: Double
        get() = backoffJitter
        set(value) {
            require(value in 0.0..1.0) { "randomizationFactor must be between 0 and 1" }
            backoffJitter = value
            executor.execute { backoff.jitter = value }
        }

    /** Connection timeout (`timeout()`); `null` disables it. */
    public var timeout: Duration?
        get() = connectTimeout
        set(value) {
            connectTimeout = value
        }

    /**
     * Calls [listener] for manager events of type [T] (use [ManagerEvent] for
     * all of them); see [SocketManagerOptions.Builder.callbackDispatcher] for
     * the thread.
     */
    public inline fun <reified T : ManagerEvent> on(noinline listener: (T) -> Unit): Subscription = on(T::class.java, listener)

    /** Calls [listener] for events of [type] only. */
    public fun <T : ManagerEvent> on(
        type: Class<T>,
        listener: (T) -> Unit,
    ): Subscription = listeners.add(type, false, listener)

    /**
     * The socket for namespace [nsp], created on first use (`manager.socket()`).
     * An existing inactive socket is reconnected when `autoConnect` is on.
     */
    public fun socket(
        nsp: String = "/",
        options: SocketOptions = SocketOptions.DEFAULT,
    ): Socket {
        var created = false
        val socket =
            nsps.computeIfAbsent(nsp) {
                created = true
                Socket(this, nsp, options)
            }
        if (created) {
            if (this.options.autoConnect) socket.connect()
        } else if (this.options.autoConnect) {
            executor.execute { if (!socket.isActive) socket.connectOnExecutor() }
        }
        return socket
    }

    /** The sockets created so far, by namespace. */
    public val sockets: Map<String, Socket> get() = HashMap(nsps)

    /** Opens the connection if it is not open or opening (`manager.open()`). */
    public fun open(): SocketManager {
        executor.execute { openOnExecutor(null) }
        return this
    }

    /** Alias of [open] (`manager.connect()`). */
    public fun connect(): SocketManager = open()

    /**
     * Opens the connection and reports the outcome once: `null` on success,
     * the error otherwise. No automatic reconnection follows a failure of this
     * attempt, as with `manager.open(callback)` in JavaScript.
     */
    public fun open(callback: (Throwable?) -> Unit): SocketManager {
        executor.execute { openOnExecutor { error -> deliver { callback(error) } } }
        return this
    }

    /**
     * Closes the connection and every socket, and stops reconnecting. The
     * manager can be opened again.
     */
    public fun disconnect() {
        executor.execute {
            for (socket in nsps.values) socket.disconnectOnExecutor()
            closeOnExecutor()
        }
    }

    /** Disconnects and releases the executor; the manager cannot be used afterwards. */
    override fun close() {
        executor.execute {
            for (socket in nsps.values) socket.disconnectOnExecutor()
            closeOnExecutor()
            executor.post { executor.shutdown() }
        }
    }

    /**
     * Informs the manager about network availability, for example from
     * Android's `ConnectivityManager`. While unavailable, a due reconnection
     * attempt waits instead of failing; when the network returns, a pending
     * attempt starts immediately instead of finishing its backoff delay.
     */
    public fun setNetworkAvailable(available: Boolean) {
        executor.execute {
            val wasAvailable = networkAvailable
            networkAvailable = available
            if (available && !wasAvailable) reconnectNowOnExecutor()
        }
    }

    /** Starts a pending reconnection attempt now instead of waiting for the backoff delay. */
    public fun reconnectNow() {
        executor.execute { reconnectNowOnExecutor() }
    }

    /** Closes the engine with `"transport close"`, as JavaScript does on the browser `offline` event. */
    public fun onNetworkLost() {
        executor.execute { engine?.onNetworkLost() }
    }

    // ---- Protocol (executor only) ----------------------------------------------------------------

    internal fun openOnExecutor(fn: ((Throwable?) -> Unit)?) {
        if (readyState == ManagerState.OPENING || readyState == ManagerState.OPEN) return
        val socket = EngineSocket(uri, options.engine, executor)
        engine = socket
        readyState = ManagerState.OPENING
        skipReconnect = false

        val openSub =
            socket.events.on<EngineEvent.Open, EngineEvent> {
                onopen()
                fn?.invoke(null)
            }
        lateinit var onError: (Throwable) -> Unit
        onError = { err ->
            cleanup()
            readyState = ManagerState.CLOSED
            emit(ManagerEvent.Error(err))
            if (fn != null) fn(err) else maybeReconnectOnOpen()
        }
        val errorSub = socket.events.on<EngineEvent.Error, EngineEvent> { onError(it.error) }
        connectTimeout?.let { timeout ->
            val timer =
                executor.schedule(timeout) {
                    openSub.cancel()
                    onError(EngineIOException("timeout"))
                    socket.close()
                }
            subs.add(timer)
        }
        subs.add(openSub)
        subs.add(errorSub)
        socket.open()
    }

    private fun maybeReconnectOnOpen() {
        if (!reconnecting && reconnectionEnabled && backoff.attempts == 0) reconnect()
    }

    private fun onopen() {
        cleanup()
        readyState = ManagerState.OPEN
        val socket = engine!!
        transportFlow.value = socket.transport?.name
        emit(ManagerEvent.Open)
        subs +=
            listOf(
                socket.events.on<EngineEvent.Ping, EngineEvent> { emit(ManagerEvent.Ping) },
                socket.events.on<EngineEvent.Message, EngineEvent> { ondata(it.data) },
                socket.events.on<EngineEvent.Error, EngineEvent> { emit(ManagerEvent.Error(it.error)) },
                socket.events.on<EngineEvent.Close, EngineEvent> {
                    onclose(DisconnectReason.fromWireValue(it.reason) ?: DisconnectReason.TRANSPORT_CLOSE, it.description)
                },
                socket.events.on<EngineEvent.Upgrade, EngineEvent> { transportFlow.value = it.transport.name },
            )
    }

    private fun ondata(data: EngineIOData) {
        try {
            val packet = decoder.add(data)
            if (packet != null) {
                reconstructionTimer?.cancel()
                reconstructionTimer = null
                ondecoded(packet)
            } else if (decoder.isReconstructing && reconstructionTimer == null) {
                val limit = options.bufferLimits.binaryReconstructionTimeout
                if (limit.isFinite()) {
                    reconstructionTimer =
                        executor.schedule(limit) {
                            reconstructionTimer = null
                            onclose(
                                DisconnectReason.PARSE_ERROR,
                                SocketBufferLimitException(SocketBufferLimitException.Buffer.BINARY_RECONSTRUCTION, 0, 0, false),
                            )
                        }
                }
            }
        } catch (e: SocketIOParseException) {
            onclose(DisconnectReason.PARSE_ERROR, e)
        }
    }

    private fun ondecoded(packet: SocketIOPacket) {
        // nextTick, as in JavaScript: the packet is handled after the current transport callback.
        executor.post { emit(ManagerEvent.Packet(packet)) }
    }

    /** The current reconnection attempt number, `0` outside reconnection. */
    internal fun currentAttempt(): Int = backoff.attempts

    /** Closes the connection because of a local failure, as the engine's `_onError` does. */
    internal fun engineErrorClose(error: Throwable) {
        emit(ManagerEvent.Error(error))
        onclose(DisconnectReason.TRANSPORT_ERROR, error)
    }

    /** Called by a socket that became inactive; closes the connection when no socket is active any more. */
    internal fun destroy() {
        for (socket in nsps.values) {
            if (socket.isActive) return
        }
        closeOnExecutor()
    }

    internal fun packet(
        packet: SocketIOPacket,
        compress: Boolean,
    ) {
        val engine = engine ?: return
        val packetOptions = if (compress) EngineIOPacketOptions.DEFAULT else EngineIOPacketOptions(compress = false)
        for (frame in encoder.encode(packet)) engine.send(frame, packetOptions)
    }

    private fun cleanup() {
        subs.forEach(Cancellable::cancel)
        subs.clear()
        reconstructionTimer?.cancel()
        reconstructionTimer = null
        decoder.destroy()
    }

    /** `_close()`: stop reconnecting and close with `"forced close"`. */
    internal fun closeOnExecutor() {
        skipReconnect = true
        reconnecting = false
        reconnectWaitingForNetwork = false
        onclose(DisconnectReason.FORCED_CLOSE, null)
    }

    private fun onclose(
        reason: DisconnectReason,
        description: Any?,
    ) {
        cleanup()
        engine?.close()
        backoff.reset()
        readyState = ManagerState.CLOSED
        transportFlow.value = null
        emit(ManagerEvent.Close(reason, DisconnectDetails.from(description)))
        if (reconnectionEnabled && !skipReconnect) reconnect()
    }

    private fun reconnect() {
        if (reconnecting || skipReconnect) return
        if (backoff.attempts >= reconnectionAttemptsLimit) {
            backoff.reset()
            emit(ManagerEvent.ReconnectFailed)
            reconnecting = false
        } else {
            val delay = backoff.duration()
            reconnecting = true
            val timer = executor.schedule(delay) { attemptReconnect() }
            reconnectTimer = timer
            subs.add(timer)
        }
    }

    private fun attemptReconnect() {
        reconnectTimer = null
        if (skipReconnect) return
        if (!networkAvailable) {
            // Keep the attempt pending until the network returns, without using up an attempt.
            reconnectWaitingForNetwork = true
            return
        }
        reconnectWaitingForNetwork = false
        emit(ManagerEvent.ReconnectAttempt(backoff.attempts))
        if (skipReconnect) return
        openOnExecutor { err ->
            if (err != null) {
                reconnecting = false
                reconnect()
                emit(ManagerEvent.ReconnectError(err))
            } else {
                onreconnect()
            }
        }
    }

    private fun reconnectNowOnExecutor() {
        if (!reconnecting || skipReconnect) return
        val timer = reconnectTimer
        if (timer != null) {
            timer.cancel()
            subs.remove(timer)
            attemptReconnect()
        } else if (reconnectWaitingForNetwork) {
            attemptReconnect()
        }
    }

    private fun onreconnect() {
        val attempt = backoff.attempts
        reconnecting = false
        backoff.reset()
        emit(ManagerEvent.Reconnect(attempt))
    }

    /** Emits to sockets synchronously, then to the public listeners and flow. */
    internal fun emit(event: ManagerEvent) {
        if (event is ManagerEvent.Error) options.logger.let { if (it.isLoggable(LogLevel.DEBUG)) it.log(LogLevel.DEBUG, "manager", "error", event.error) }
        internalEvents.emit(event)
        eventFlow.tryEmit(event)
        listeners.dispatch(event, this)
    }

    /** Runs a user callback on the configured callback dispatcher, isolating its exceptions. */
    internal fun deliver(block: () -> Unit) {
        val dispatcher = options.callbackDispatcher
        if (dispatcher == null) {
            guarded(block)
        } else {
            executor.scope.launch(dispatcher) { guarded(block) }
        }
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            reportListenerError(e)
        }
    }

    internal fun reportListenerError(error: Throwable) {
        val handler = options.listenerErrorHandler
        if (handler != null) {
            handler(error)
        } else {
            options.logger.log(LogLevel.ERROR, "listener", "a listener threw", error)
            if (!options.logger.isLoggable(LogLevel.ERROR)) {
                System.err.println("socket.io listener threw: $error")
                error.printStackTrace()
            }
        }
    }

    internal enum class ManagerState { OPENING, OPEN, CLOSED }
}

/** A registration that can be cancelled; cancelling twice is harmless. */
public fun interface Subscription : Cancellable

/** Thread-safe listener lists delivering through the manager's callback dispatcher. */
internal class CallbackListeners<E : Any> {
    private class Entry<E>(
        val type: Class<out E>,
        val once: Boolean,
        val listener: (E) -> Unit,
    )

    private val entries = java.util.concurrent.CopyOnWriteArrayList<Entry<E>>()

    fun <T : E> add(
        type: Class<T>,
        once: Boolean,
        listener: (T) -> Unit,
    ): Subscription {
        @Suppress("UNCHECKED_CAST")
        val entry = Entry<E>(type, once, listener as (E) -> Unit)
        entries.add(entry)
        return Subscription { entries.remove(entry) }
    }

    fun dispatch(
        event: E,
        manager: SocketManager,
    ) {
        for (entry in entries) {
            if (!entry.type.isInstance(event)) continue
            if (entry.once && !entries.remove(entry)) continue
            manager.deliver { entry.listener(event) }
        }
    }

    fun clear() = entries.clear()
}
