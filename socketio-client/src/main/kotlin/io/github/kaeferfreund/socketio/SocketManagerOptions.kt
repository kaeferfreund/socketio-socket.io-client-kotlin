package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.EngineClients
import io.github.kaeferfreund.socketio.engineio.EngineOptions
import io.github.kaeferfreund.socketio.engineio.EnginePacketObserver
import io.github.kaeferfreund.socketio.engineio.EngineTransport
import io.github.kaeferfreund.socketio.engineio.HandshakeLimits
import io.github.kaeferfreund.socketio.engineio.PollingTransport
import io.github.kaeferfreund.socketio.engineio.SocketLogger
import io.github.kaeferfreund.socketio.engineio.TransportOverrides
import io.github.kaeferfreund.socketio.engineio.WebSocketTransport
import io.github.kaeferfreund.socketio.parser.SocketParserOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Transport names for [SocketManagerOptions.Builder.transports]. */
public object Transport {
    /** HTTP long-polling. */
    public const val POLLING: String = PollingTransport.NAME

    /** WebSocket. */
    public const val WEBSOCKET: String = WebSocketTransport.NAME
}

/**
 * Bounds for everything the client retains across packets. **Every limit is
 * off by default**, which is the JavaScript behaviour; setting one opts into
 * a hard bound with an explicit failure:
 *
 * - Outgoing (send buffer, retry queue): the *new* emit fails with
 *   [SocketBufferLimitException] through its acknowledgement callback and the
 *   manager's error event; nothing already accepted is evicted.
 * - Incoming (receive buffer before CONNECT): the connection closes with
 *   `"transport error"` and reconnects normally.
 * - [binaryReconstructionTimeout]: a binary packet still missing attachments
 *   after this time closes the connection with `"parse error"`.
 */
public class SocketBufferLimits(
    public val maxSendBufferPackets: Int = Int.MAX_VALUE,
    public val maxSendBufferBytes: Long = Long.MAX_VALUE,
    public val maxRetryQueuePackets: Int = Int.MAX_VALUE,
    public val maxRetryQueueBytes: Long = Long.MAX_VALUE,
    public val maxReceiveBufferPackets: Int = Int.MAX_VALUE,
    public val maxReceiveBufferBytes: Long = Long.MAX_VALUE,
    public val binaryReconstructionTimeout: Duration = Duration.INFINITE,
) {
    init {
        require(maxSendBufferPackets > 0 && maxSendBufferBytes > 0) { "send buffer limits must be positive" }
        require(maxRetryQueuePackets > 0 && maxRetryQueueBytes > 0) { "retry queue limits must be positive" }
        require(maxReceiveBufferPackets > 0 && maxReceiveBufferBytes > 0) { "receive buffer limits must be positive" }
        require(binaryReconstructionTimeout.isPositive()) { "binaryReconstructionTimeout must be positive" }
    }

    public companion object {
        /** No limits: the JavaScript behaviour. */
        public val UNLIMITED: SocketBufferLimits = SocketBufferLimits()
    }
}

/** Observes outgoing emits, for apps that persist unsent work (for example with WorkManager). */
public interface OutgoingInterceptor {
    /** The emit was buffered because the socket is not connected. */
    public fun onBuffered(event: OutgoingEvent) {}

    /** The emit was handed to the transport. */
    public fun onSent(event: OutgoingEvent) {}

    /** The emit was discarded: volatile without a writable transport, timed out while buffered, or over a limit. */
    public fun onDropped(
        event: OutgoingEvent,
        reason: Throwable?,
    ) {}
}

/** Options of one namespace socket, `SocketOptions` of `socket.io-client`. */
public class SocketOptions private constructor(
    /** Static CONNECT payload (the object form of JavaScript `auth`). */
    public val auth: Any?,
    /** Dynamic CONNECT payload, evaluated before every CONNECT. Wins over [auth]. */
    public val authProvider: AuthProvider?,
    /** Resend unacknowledged emits this many times, in order (`retries`). `0` disables the queue. */
    public val retries: Int,
    /** Default acknowledgement timeout (`ackTimeout`); `null` waits forever. */
    public val ackTimeout: Duration?,
    public val outgoingInterceptor: OutgoingInterceptor?,
) {
    /** Builder for [SocketOptions]. */
    public class Builder internal constructor() {
        public var auth: Any? = null
        public var authProvider: AuthProvider? = null
        public var retries: Int = 0
        public var ackTimeout: Duration? = null
        public var outgoingInterceptor: OutgoingInterceptor? = null

        public fun build(): SocketOptions {
            require(retries >= 0) { "retries must not be negative" }
            require(ackTimeout == null || !ackTimeout!!.isNegative()) { "ackTimeout must not be negative" }
            return SocketOptions(auth, authProvider, retries, ackTimeout, outgoingInterceptor)
        }
    }

    public companion object {
        /** No auth, no retries, no timeout. */
        public val DEFAULT: SocketOptions = Builder().build()
    }
}

/** Builds [SocketOptions]. */
public fun SocketOptions(block: SocketOptions.Builder.() -> Unit = {}): SocketOptions = SocketOptions.Builder().apply(block).build()

/**
 * Options of a [SocketManager]: the JavaScript `ManagerOptions` (which include
 * the Engine.IO options) plus the Kotlin runtime settings. Every default is
 * the JavaScript default.
 */
public class SocketManagerOptions private constructor(
    builder: Builder,
) {
    public val forceNew: Boolean = builder.forceNew
    public val multiplex: Boolean = builder.multiplex
    public val reconnection: Boolean = builder.reconnection
    public val reconnectionAttempts: Int = builder.reconnectionAttempts
    public val reconnectionDelay: Duration = builder.reconnectionDelay
    public val reconnectionDelayMax: Duration = builder.reconnectionDelayMax
    public val randomizationFactor: Double = builder.randomizationFactor
    public val timeout: Duration? = builder.timeout
    public val autoConnect: Boolean = builder.autoConnect
    public val parserOptions: SocketParserOptions = builder.parserOptions
    public val bufferLimits: SocketBufferLimits = builder.bufferLimits
    public val dispatcher: CoroutineDispatcher = builder.dispatcher
    public val timeSource: TimeSource.WithComparableMarks = builder.timeSource
    public val callbackDispatcher: CoroutineDispatcher? = builder.callbackDispatcher
    public val listenerErrorHandler: ((Throwable) -> Unit)? = builder.listenerErrorHandler
    public val random: Random = builder.random
    public val logger: SocketLogger = builder.logger

    /** The Engine.IO options derived from these options. */
    public val engine: EngineOptions =
        EngineOptions(
            path = builder.path,
            query = builder.query,
            upgrade = builder.upgrade,
            forceBase64 = builder.forceBase64,
            timestampParam = builder.timestampParam,
            timestampRequests = builder.timestampRequests,
            transports = builder.transports,
            tryAllTransports = builder.tryAllTransports,
            rememberUpgrade = builder.rememberUpgrade,
            requestTimeout = builder.requestTimeout,
            transportOptions = builder.transportOptions,
            extraHeaders = builder.extraHeaders,
            withCredentials = builder.withCredentials,
            protocols = builder.protocols,
            perMessageDeflateThreshold = builder.perMessageDeflateThreshold,
            addTrailingSlash = builder.addTrailingSlash,
            transportFactories = builder.transportFactories,
            clients = builder.clients,
            maxPollingResponseBytes = builder.maxPollingResponseBytes,
            handshakeLimits = builder.handshakeLimits,
            logger = builder.logger,
            packetObserver = builder.packetObserver,
        )

    private val extensions: Map<Key<*>, Any> = HashMap(builder.extensions)

    /** A typed slot for integrations such as the Android module. */
    public class Key<T : Any>(
        public val name: String,
    ) {
        override fun toString(): String = "Key($name)"
    }

    /** The value stored for [key], or `null`. */
    public operator fun <T : Any> get(key: Key<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return extensions[key] as T?
    }

    /** A builder pre-filled with these options. */
    public fun newBuilder(): Builder = Builder(this)

    /** Mutable builder; see [SocketManagerOptions] for the meaning of each option. */
    public class Builder internal constructor(
        from: SocketManagerOptions? = null,
    ) {
        /** Always create a new manager in [SocketIO.io] (`forceNew`). */
        public var forceNew: Boolean = from?.forceNew ?: false

        /** Share managers per URL in [SocketIO.io] (`multiplex`). */
        public var multiplex: Boolean = from?.multiplex ?: true

        /** Reconnect automatically (`reconnection`). */
        public var reconnection: Boolean = from?.reconnection ?: true

        /** Maximum reconnection attempts; `Int.MAX_VALUE` is infinite (`reconnectionAttempts`). */
        public var reconnectionAttempts: Int = from?.reconnectionAttempts ?: Int.MAX_VALUE

        /** Initial reconnection delay (`reconnectionDelay`). */
        public var reconnectionDelay: Duration = from?.reconnectionDelay ?: 1.seconds

        /** Maximum reconnection delay (`reconnectionDelayMax`). */
        public var reconnectionDelayMax: Duration = from?.reconnectionDelayMax ?: 5.seconds

        /** Jitter between 0 and 1 (`randomizationFactor`). */
        public var randomizationFactor: Double = from?.randomizationFactor ?: 0.5

        /** Connection timeout of each attempt; `null` disables it, zero fails every attempt at once (`timeout`). */
        public var timeout: Duration? = if (from != null) from.timeout else 20.seconds

        /** Connect on creation (`autoConnect`). */
        public var autoConnect: Boolean = from?.autoConnect ?: true

        /** Limits of one incoming packet. */
        public var parserOptions: SocketParserOptions = from?.parserOptions ?: SocketParserOptions.DEFAULT

        /** Limits of the buffers kept across packets. */
        public var bufferLimits: SocketBufferLimits = from?.bufferLimits ?: SocketBufferLimits.UNLIMITED

        /** Dispatcher the protocol runs on; limited to parallelism 1. */
        public var dispatcher: CoroutineDispatcher = from?.dispatcher ?: Dispatchers.Default

        /** Monotonic clock for heartbeat, backoff and acknowledgement deadlines. */
        public var timeSource: TimeSource.WithComparableMarks = from?.timeSource ?: TimeSource.Monotonic

        /**
         * Dispatcher for listener callbacks. `null` (the default in the core)
         * calls listeners directly on the protocol executor, which keeps the
         * JavaScript ordering; the Android module defaults to the main thread.
         */
        public var callbackDispatcher: CoroutineDispatcher? = from?.callbackDispatcher

        /** Receives exceptions thrown by listeners; they never corrupt protocol state. Default: log them. */
        public var listenerErrorHandler: ((Throwable) -> Unit)? = from?.listenerErrorHandler

        /** Randomness for the reconnection jitter. */
        public var random: Random = from?.random ?: Random.Default

        public var logger: SocketLogger = from?.logger ?: SocketLogger.NONE

        /** Request path (`path`), `/socket.io` by default. */
        public var path: String = from?.engine?.path ?: "/socket.io"

        /** Query parameters of every request (`query`). */
        public var query: Map<String, String> = from?.engine?.query ?: emptyMap()

        public var upgrade: Boolean = from?.engine?.upgrade ?: true
        public var forceBase64: Boolean = from?.engine?.forceBase64 ?: false
        public var timestampParam: String = from?.engine?.timestampParam ?: "t"
        public var timestampRequests: Boolean? = from?.engine?.timestampRequests
        public var transports: List<String> = from?.engine?.transports ?: listOf(Transport.POLLING, Transport.WEBSOCKET)
        public var tryAllTransports: Boolean = from?.engine?.tryAllTransports ?: false
        public var rememberUpgrade: Boolean = from?.engine?.rememberUpgrade ?: false
        public var requestTimeout: Duration? = from?.engine?.requestTimeout
        public var transportOptions: Map<String, TransportOverrides> = from?.engine?.transportOptions ?: emptyMap()
        public var extraHeaders: Map<String, String> = from?.engine?.extraHeaders ?: emptyMap()
        public var withCredentials: Boolean = from?.engine?.withCredentials ?: false
        public var protocols: List<String> = from?.engine?.protocols ?: emptyList()
        public var perMessageDeflateThreshold: Int? = if (from != null) from.engine.perMessageDeflateThreshold else 1024
        public var addTrailingSlash: Boolean = from?.engine?.addTrailingSlash ?: true
        public var transportFactories: Map<String, EngineTransport.Factory> = from?.engine?.transportFactories ?: emptyMap()

        /** HTTP/WebSocket stacks; discovered on the classpath (`socketio-okhttp`) when `null`. */
        public var clients: EngineClients? = from?.engine?.clients
        public var maxPollingResponseBytes: Long = from?.engine?.maxPollingResponseBytes ?: Long.MAX_VALUE
        public var handshakeLimits: HandshakeLimits = from?.engine?.handshakeLimits ?: HandshakeLimits()

        /** Sees every Engine.IO packet in both directions (debugging, logging, tests). */
        public var packetObserver: EnginePacketObserver? = from?.engine?.packetObserver

        internal val extensions: MutableMap<Key<*>, Any> = HashMap(from?.extensions ?: emptyMap())

        /** Stores an integration value under [key]. */
        public fun <T : Any> set(
            key: Key<T>,
            value: T,
        ) {
            extensions[key] = value
        }

        /** The integration value under [key], or `null`. */
        public fun <T : Any> get(key: Key<T>): T? {
            @Suppress("UNCHECKED_CAST")
            return extensions[key] as T?
        }

        public fun build(): SocketManagerOptions {
            require(reconnectionAttempts >= 0) { "reconnectionAttempts must not be negative" }
            require(!reconnectionDelay.isNegative() && !reconnectionDelayMax.isNegative()) { "reconnection delays must not be negative" }
            require(randomizationFactor in 0.0..1.0) { "randomizationFactor must be between 0 and 1" }
            require(timeout == null || !timeout!!.isNegative()) { "timeout must not be negative" }
            return SocketManagerOptions(this)
        }
    }

    public companion object {
        /** The JavaScript defaults. */
        public val DEFAULT: SocketManagerOptions = Builder().build()
    }
}

/** Builds [SocketManagerOptions]. */
public fun SocketManagerOptions(block: SocketManagerOptions.Builder.() -> Unit = {}): SocketManagerOptions =
    SocketManagerOptions.Builder().apply(block).build()
