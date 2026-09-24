package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.IncomingEvent
import io.github.kaeferfreund.socketio.Socket
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.testing.FixtureServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The repository's Node fixtures. */
internal val fixturesDir: File = File(System.getProperty("socketio.fixtures") ?: "../fixtures")

/** Starts a fixture, runs [block] and always stops the server and every manager created through [E2EScope.manager]. */
internal fun e2e(
    script: String = "upstream-server.mjs",
    environment: Map<String, String> = emptyMap(),
    timeout: Duration = 30.seconds,
    block: suspend E2EScope.() -> Unit,
) {
    FixtureServer.start(fixturesDir, script, environment).use { server ->
        val scope = E2EScope(server)
        try {
            runBlocking { withTimeout(timeout) { scope.block() } }
        } catch (e: Throwable) {
            throw AssertionError("E2E failure; server log:\n${server.log}", e)
        } finally {
            scope.managers.forEach { it.close() }
        }
    }
}

internal class E2EScope(
    val server: FixtureServer,
) {
    val managers = ArrayList<SocketManager>()

    val url: String get() = server.url

    fun manager(
        uri: String = url,
        setup: (SocketManager.() -> Unit)? = null,
        block: SocketManagerOptions.Builder.() -> Unit = {},
    ): SocketManager = SocketManager(uri, SocketManagerOptions(block), setup).also { managers.add(it) }

    /** `io(BASE_URL + path, { forceNew: true, … })`. */
    fun io(
        path: String = "",
        socketOptions: io.github.kaeferfreund.socketio.SocketOptions = io.github.kaeferfreund.socketio.SocketOptions.DEFAULT,
        setup: (Socket.() -> Unit)? = null,
        block: SocketManagerOptions.Builder.() -> Unit = {},
    ): Socket {
        val socket =
            io.github.kaeferfreund.socketio.SocketIO.io(
                url + path,
                SocketManagerOptions {
                    forceNew = true
                    block()
                },
                socketOptions,
                setup,
            )
        managers.add(socket.manager)
        return socket
    }

    /** Closes the server side of [socket]'s connection, the native replacement for `socket.io.engine.close()` in the JavaScript suite. */
    fun killTransport(socket: Socket) {
        val sid = requireNotNull(socket.id) { "socket not connected" }
        val (status, body) = server.admin("/admin/kill-transport?sid=$sid&nsp=${java.net.URLEncoder.encode(socket.namespace, "UTF-8")}")
        check(status == 200) { "kill-transport failed: $status $body" }
    }
}

/** Fails the test if [block] is ever called; the returned list holds the failures. */
internal class Unexpected {
    private val failures = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun fail(message: String) {
        failures.add(message)
    }

    fun check() {
        if (failures.isNotEmpty()) throw AssertionError("unexpected: $failures")
    }
}

/** Collects events of [name] as they arrive. */
internal fun Socket.channel(name: String): Channel<IncomingEvent> {
    val channel = Channel<IncomingEvent>(Channel.UNLIMITED)
    on(name) { channel.trySend(it) }
    return channel
}

internal suspend fun <T> Channel<T>.next(timeout: Duration = 10.seconds): T = withTimeout(timeout) { receive() }

internal suspend fun Socket.awaitConnect(timeout: Duration = 10.seconds) {
    if (connected) return
    val deferred = CompletableDeferred<Unit>()
    val subscription = onConnect { deferred.complete(Unit) }
    try {
        if (connected) return
        withTimeout(timeout) { deferred.await() }
    } finally {
        subscription.cancel()
    }
}

internal suspend fun <T> await(
    timeout: Duration = 10.seconds,
    register: (CompletableDeferred<T>) -> Unit,
): T {
    val deferred = CompletableDeferred<T>()
    register(deferred)
    return withTimeout(timeout) { deferred.await() }
}
