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
        block: SocketManagerOptions.Builder.() -> Unit = {},
    ): SocketManager = SocketManager(uri, SocketManagerOptions(block)).also { managers.add(it) }
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
