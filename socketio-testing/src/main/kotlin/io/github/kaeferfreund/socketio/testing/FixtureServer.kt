package io.github.kaeferfreund.socketio.testing

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a Node fixture server (from the repository's `fixtures` directory, or
 * your own directory with the same `READY port=… secret=…` convention) for
 * end-to-end tests on the JVM.
 *
 * ```kotlin
 * FixtureServer.start(fixturesDir, "server.js").use { server ->
 *     val manager = SocketManager(server.url)
 * }
 * ```
 */
public class FixtureServer private constructor(
    /** The port the server listens on (on `127.0.0.1`). */
    public val port: Int,
    /** The secret expected in the `X-Admin-Secret` header of admin routes. */
    public val secret: String,
    private val process: Process,
    private val output: StringBuffer,
    private val scheme: String,
) : AutoCloseable {
    /** `http://127.0.0.1:<port>` (or `https://localhost:<port>` for TLS fixtures). */
    public val url: String get() = if (scheme == "https") "https://localhost:$port" else "http://127.0.0.1:$port"

    /** Everything the server printed so far, for diagnostics. */
    public val log: String get() = output.toString()

    /** Calls an admin route and returns the status and body. */
    public fun admin(
        path: String,
        method: String = "POST",
        body: String? = null,
    ): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("X-Admin-Secret", secret)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to (stream?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    /** Stops the server: SIGTERM, then SIGKILL after one second. */
    override fun close() {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }

    public companion object {
        private val installLock = Any()

        /**
         * Starts [script] in [fixturesDir] with [environment] and waits until
         * it prints its READY line.
         */
        public fun start(
            fixturesDir: File,
            script: String = "server.js",
            environment: Map<String, String> = emptyMap(),
            startupTimeout: Duration = 15.seconds,
            tls: Boolean = false,
        ): FixtureServer {
            ensureNodeModules(fixturesDir)
            val builder = ProcessBuilder("node", script).directory(fixturesDir).redirectErrorStream(true)
            builder.environment().putAll(environment)
            val process = builder.start()
            val output = StringBuffer()
            val ready = java.util.concurrent.CompletableFuture<Pair<Int, String>>()
            Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (output.length < 256 * 1024) output.append(line).append('\n')
                        READY.find(line)?.let { ready.complete(it.groupValues[1].toInt() to it.groupValues[2]) }
                    }
                }
                ready.completeExceptionally(IllegalStateException("fixture exited before READY:\n$output"))
            }, "fixture-$script").apply { isDaemon = true }.start()
            val (port, secret) =
                try {
                    ready.get(startupTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    process.destroyForcibly()
                    throw IllegalStateException("fixture $script did not start in $startupTimeout:\n$output", e)
                } catch (e: java.util.concurrent.ExecutionException) {
                    process.destroyForcibly()
                    throw IllegalStateException(e.cause?.message ?: "fixture failed", e)
                }
            return FixtureServer(port, secret, process, output, if (tls) "https" else "http")
        }

        /** Runs `npm ci` once per lockfile content. */
        public fun ensureNodeModules(fixturesDir: File) {
            synchronized(installLock) {
                val lock = File(fixturesDir, "package-lock.json").readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(lock).joinToString("") { "%02x".format(it) }
                val marker = File(fixturesDir, "node_modules/.kotlin-fixture-lock")
                if (marker.isFile && marker.readText() == digest) return
                val process =
                    ProcessBuilder("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund")
                        .directory(fixturesDir)
                        .redirectErrorStream(true)
                        .start()
                val log = process.inputStream.bufferedReader().readText()
                check(process.waitFor(180, TimeUnit.SECONDS) && process.exitValue() == 0) { "npm ci failed:\n$log" }
                marker.writeText(digest)
            }
        }

        private val READY = Regex("READY port=(\\d+) secret=(\\S+)")
    }
}
