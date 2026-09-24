package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.SocketConnectException
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.Transport
import io.github.kaeferfreund.socketio.okhttp.TlsPolicy
import io.github.kaeferfreund.socketio.okhttp.okHttp
import io.github.kaeferfreund.socketio.testing.FixtureServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.CertificatePinner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

/**
 * HTTPS and WSS against a real Node server with a private PKI generated for
 * this run by `generate-native-tls.mjs` (the Swift fork's generator): trust
 * anchors, expired certificates, host names, pins and mutual TLS (KT-TLS).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsE2ETest {
    private lateinit var pki: File

    @BeforeAll
    fun generatePki() {
        pki = Files.createTempDirectory("socketio-tls").toFile()
        FixtureServer.ensureNodeModules(fixturesDir)
        val process = ProcessBuilder("node", "generate-native-tls.mjs", pki.absolutePath).directory(fixturesDir).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "PKI generation failed:\n$log" }
    }

    @AfterAll
    fun deletePki() {
        pki.deleteRecursively()
    }

    private val ca get() = TlsPolicy.certificates(File(pki, "ca.pem").readBytes())
    private val leaf get() = TlsPolicy.certificates(File(pki, "leaf.pem").readBytes())

    private fun tls(
        environment: Map<String, String> = emptyMap(),
        block: suspend (FixtureServer) -> Unit,
    ) {
        FixtureServer.start(fixturesDir, "native-tls-server.mjs", mapOf("NATIVE_TLS_DIR" to pki.absolutePath) + environment, tls = true).use { server ->
            runBlocking { withTimeout(30.seconds) { block(server) } }
        }
    }

    /** Connects and returns the echo of "secure", or the connection error. */
    private suspend fun echo(
        url: String,
        policy: TlsPolicy,
        transports: List<String> = listOf(Transport.POLLING, Transport.WEBSOCKET),
    ): Result<String?> {
        val outcome = CompletableDeferred<Result<String?>>()
        val manager =
            SocketManager(
                url,
                SocketManagerOptions {
                    this.transports = transports
                    reconnection = false
                    okHttp { tlsPolicy = policy }
                },
            )
        try {
            val socket =
                manager.socket("/") {
                    onConnectError { outcome.complete(Result.failure(it)) }
                    onConnect { emit("echo", "secure") { outcome.complete(it.map { args -> args[0].string }) } }
                }
            val result = withTimeout(15.seconds) { outcome.await() }
            socket.disconnect()
            return result
        } finally {
            manager.close()
        }
    }

    @Test
    fun connectsOverHttpsAndWssWithAPrivateCa() =
        tls { server ->
            for (transports in listOf(listOf(Transport.POLLING), listOf(Transport.WEBSOCKET), listOf(Transport.POLLING, Transport.WEBSOCKET))) {
                assertEquals("secure", echo(server.url, TlsPolicy.customTrust(ca), transports).getOrThrow(), "$transports")
            }
        }

    private fun Throwable?.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    @Test
    fun rejectsTheServerWithTheSystemTrustStore() =
        tls { server ->
            val error = echo(server.url, TlsPolicy.systemDefault()).exceptionOrNull()
            assertTrue(error.causes().any { it is SSLException }, "$error")
        }

    @Test
    fun rejectsAnExpiredCertificate() =
        tls(mapOf("NATIVE_TLS_EXPIRED" to "1")) { server ->
            val error = echo(server.url, TlsPolicy.customTrust(ca)).exceptionOrNull()
            assertTrue(error is io.github.kaeferfreund.socketio.engineio.TransportException, "$error")
            assertTrue(error.causes().any { it is SSLException || it is java.security.cert.CertificateException }, "${error.causes()}")
            assertTrue(error !is SocketConnectException)
        }

    @Test
    fun rejectsAHostNameMismatch() =
        tls { server ->
            // The leaf is issued for localhost only.
            val error = echo("https://127.0.0.1:${server.port}", TlsPolicy.customTrust(ca)).exceptionOrNull()
            assertTrue(error.causes().any { it is javax.net.ssl.SSLPeerUnverifiedException || it is SSLException }, "${error.causes()}")
        }

    @Test
    fun enforcesPublicKeyPins() =
        tls { server ->
            val trust = TlsPolicy.customTrust(ca)
            assertEquals("secure", echo(server.url, trust.withPinnedCertificates("localhost", leaf)).getOrThrow())
            val wrong = trust.withPins("localhost", CertificatePinner.pin(okhttp3.tls.HeldCertificate.Builder().build().certificate))
            assertTrue(echo(server.url, wrong).isFailure)
        }

    @Test
    fun presentsAClientCertificateForMutualTls() =
        tls(mapOf("NATIVE_TLS_CLIENT_AUTH" to "1")) { server ->
            val trust = TlsPolicy.customTrust(ca)
            assertTrue(echo(server.url, trust).isFailure, "connected without a client certificate")
            val withClient = trust.withClientCertificate(File(pki, "client.p12").readBytes(), "fixture".toCharArray())
            assertEquals("secure", echo(server.url, withClient).getOrThrow())
            assertEquals("secure", echo(server.url, withClient, listOf(Transport.WEBSOCKET)).getOrThrow())
        }
}
