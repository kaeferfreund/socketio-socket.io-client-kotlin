package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.testing.FakeSocketIOServer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A manager wired to an in-memory Socket.IO server, entirely on the test scheduler's virtual time. */
internal class ClientHarness(
    val test: TestScope,
    val server: FakeSocketIOServer,
) {
    val managers = ArrayList<SocketManager>()

    fun manager(
        setup: (SocketManager.() -> Unit)? = null,
        block: SocketManagerOptions.Builder.() -> Unit = {},
    ): SocketManager {
        val options =
            SocketManagerOptions {
                dispatcher = StandardTestDispatcher(test.testScheduler)
                timeSource = test.testScheduler.timeSource
                clients = server.clients
                random = Random(42)
                block()
            }
        return SocketManager("http://fake.test", options, setup).also { managers.add(it) }
    }

    /** Runs everything due at the current virtual time. */
    fun settle() = test.runCurrent()

    /** Runs [block] on the manager's protocol executor, like code inside a JavaScript callback. */
    fun onExecutor(
        manager: SocketManager,
        block: () -> Unit,
    ) {
        manager.executor.execute(block)
        settle()
    }

    fun close() {
        managers.forEach { it.close() }
        settle()
    }
}

internal fun TestScope.clientHarness(
    pingInterval: Duration = 25.seconds,
    pingTimeout: Duration = 20.seconds,
    recovery: Boolean = false,
    heartbeat: Boolean = true,
    upgrades: List<String> = listOf("websocket"),
    configure: FakeSocketIOServer.() -> Unit = {},
): ClientHarness {
    val server =
        FakeSocketIOServer(backgroundScope, pingInterval, pingTimeout, heartbeat = heartbeat, recovery = recovery, upgrades = upgrades)
    server.namespace("/").onConnection { client ->
        client.on("echo") { args, ack -> ack?.invoke(args) }
        client.on("hi") { _, _ -> client.emit("hi") }
    }
    server.configure()
    return ClientHarness(this, server)
}
