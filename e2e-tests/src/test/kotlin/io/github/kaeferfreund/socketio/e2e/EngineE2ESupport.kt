package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.engineio.EngineEvent
import io.github.kaeferfreund.socketio.engineio.EngineOptions
import io.github.kaeferfreund.socketio.engineio.EngineSocket
import io.github.kaeferfreund.socketio.engineio.ProtocolExecutor
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.okhttp.OkHttpEngineClients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A real engine (OkHttp transports, real time) against a fixture, with every event recorded. */
internal class EngineClient(
    uri: String,
    options: EngineOptions,
) : AutoCloseable {
    val executor = ProtocolExecutor(Dispatchers.Default)
    val engine = EngineSocket(uri, options.copy(clients = options.clients ?: OkHttpEngineClients().clients), executor)
    val events = Channel<EngineEvent>(Channel.UNLIMITED)
    val messages = Channel<EngineIOData>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<EngineEvent.Close>()

    init {
        engine.events.on(EngineEvent::class.java) { event ->
            events.trySend(event)
            when (event) {
                is EngineEvent.Open -> opened.complete(Unit)
                is EngineEvent.Message -> messages.trySend(event.data)
                is EngineEvent.Close -> closed.complete(event)
                else -> Unit
            }
        }
    }

    fun open(): EngineClient {
        executor.execute { engine.open() }
        return this
    }

    /** Runs [block] on the protocol executor and waits for it. */
    suspend fun <T> onExecutor(block: EngineSocket.() -> T): T {
        val result = CompletableDeferred<T>()
        executor.execute { result.complete(engine.block()) }
        return withTimeout(10.seconds) { result.await() }
    }

    suspend fun awaitOpen(timeout: Duration = 10.seconds) = withTimeout(timeout) { opened.await() }

    suspend fun awaitClose(timeout: Duration = 10.seconds) = withTimeout(timeout) { closed.await() }

    suspend fun nextText(timeout: Duration = 10.seconds): String = (withTimeout(timeout) { messages.receive() } as EngineIOData.Text).value

    suspend fun nextMessage(timeout: Duration = 10.seconds): EngineIOData = withTimeout(timeout) { messages.receive() }

    suspend inline fun <reified T : EngineEvent> next(timeout: Duration = 10.seconds): T =
        withTimeout(timeout) {
            while (true) {
                val event = events.receive()
                if (event is T) return@withTimeout event
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    override fun close() {
        executor.execute { engine.close() }
        executor.post { executor.shutdown() }
    }
}

/** Runs [block] against `engine-server.mjs` (the upstream engine test server). */
internal fun engineE2E(
    environment: Map<String, String> = emptyMap(),
    block: suspend EngineE2EScope.() -> Unit,
) = e2e("engine-server.mjs", environment) {
    val scope = EngineE2EScope(this)
    try {
        scope.block()
    } finally {
        scope.clients.forEach(EngineClient::close)
    }
}

internal class EngineE2EScope(
    val outer: E2EScope,
) {
    val clients = ArrayList<EngineClient>()

    val url: String get() = outer.url

    fun engine(
        options: EngineOptions = EngineOptions(),
        uri: String = url,
        open: Boolean = true,
    ): EngineClient = EngineClient(uri, options).also { clients += it }.let { if (open) it.open() else it }
}
