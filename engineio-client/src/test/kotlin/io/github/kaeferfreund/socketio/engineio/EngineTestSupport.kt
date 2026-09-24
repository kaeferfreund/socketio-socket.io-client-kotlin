package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.testing.FakeEngineServer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds

/** An engine wired to a [FakeEngineServer], all on the test scheduler's virtual time. */
internal class EngineHarness(
    val test: TestScope,
    val server: FakeEngineServer,
    val executor: ProtocolExecutor,
) {
    val events = ArrayList<EngineEvent>()

    fun engine(
        options: EngineOptions = EngineOptions(),
        uri: String? = "http://localhost:3000",
        open: Boolean = true,
    ): EngineSocket {
        val engine = EngineSocket(uri, options.copy(clients = server.clients), executor)
        engine.events.on(EngineEvent::class.java) { events.add(it) }
        if (open) executor.execute { engine.open() }
        return engine
    }

    /** Runs everything due now, including work it schedules for now. */
    fun settle() = test.runCurrent()

    inline fun <reified T : EngineEvent> all(): List<T> = events.filterIsInstance<T>()

    val messages: List<String> get() = all<EngineEvent.Message>().mapNotNull { (it.data as? EngineIOData.Text)?.value }

    fun close() = executor.shutdown()
}

internal fun TestScope.engineHarness(
    pingInterval: kotlin.time.Duration = 25.seconds,
    pingTimeout: kotlin.time.Duration = 20.seconds,
    maxPayload: Long = 1_000_000,
    greeting: String? = "hi",
    echo: Boolean = true,
    timeSource: kotlin.time.TimeSource.WithComparableMarks = testScheduler.timeSource,
    heartbeat: Boolean = true,
): EngineHarness {
    val server = FakeEngineServer(backgroundScope, pingInterval, pingTimeout, maxPayload, heartbeat = heartbeat)
    server.onConnection = { session ->
        if (greeting != null) session.send(greeting)
        if (echo) {
            session.onMessage = { data ->
                when (data) {
                    is EngineIOData.Text -> session.send(data.value)
                    is EngineIOData.Binary -> session.send(data.bytes)
                }
            }
        }
    }
    val executor = ProtocolExecutor(StandardTestDispatcher(testScheduler), timeSource)
    return EngineHarness(this, server, executor)
}
