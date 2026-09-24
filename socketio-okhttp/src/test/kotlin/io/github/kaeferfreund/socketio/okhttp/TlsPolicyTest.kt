package io.github.kaeferfreund.socketio.okhttp

import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** [TlsPolicy] against a TLS MockWebServer with a private CA. */
class TlsPolicyTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("test CA").build()
    private val leaf = HeldCertificate.Builder().signedBy(ca).addSubjectAlternativeName("localhost").commonName("localhost").build()
    private val otherCa = HeldCertificate.Builder().certificateAuthority(0).commonName("other CA").build()
    private val server = MockWebServer()
    private val loopback = InetAddress.getByName("127.0.0.1")

    @AfterEach
    fun stop() = server.close()

    private fun startHttps(requireClientAuth: Boolean = false) {
        val certificates =
            HandshakeCertificates.Builder()
                .heldCertificate(leaf, ca.certificate)
                .addTrustedCertificate(ca.certificate)
                .build()
        server.useHttps(certificates.sslSocketFactory())
        if (requireClientAuth) server.requireClientAuth()
        server.start(loopback, 0)
    }

    private fun call(policy: TlsPolicy): Result<EngineHttpResponse> {
        server.enqueue(MockResponse.Builder().body("ok").build())
        // "localhost" must reach the server on its only address. Where it also resolves to ::1,
        // OkHttp's fast fallback would report the refused IPv6 route instead of the TLS failure.
        val clients = OkHttpEngineClients(policy.applyTo(OkHttpClient.Builder()).dns { listOf(loopback) }.build())
        val future = CompletableFuture<Result<EngineHttpResponse>>()
        val url = server.url("/socket.io/").newBuilder().host("localhost").build().toString()
        clients.execute(
            EngineHttpRequest("GET", url, emptyList(), null, null),
            object : EngineHttpCallback {
                override fun onResponse(response: EngineHttpResponse) {
                    future.complete(Result.success(response))
                }

                override fun onFailure(error: Throwable) {
                    future.complete(Result.failure(error))
                }
            },
        )
        return future.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun trustsAPrivateCa() {
        startHttps()
        assertEquals("ok", call(TlsPolicy.customTrust(listOf(ca.certificate))).getOrThrow().body)
    }

    @Test
    fun rejectsAServerOutsideTheCustomTrust() {
        startHttps()
        assertTrue(call(TlsPolicy.customTrust(listOf(otherCa.certificate))).exceptionOrNull() is SSLException)
    }

    @Test
    fun theSystemTrustStoreDoesNotTrustAPrivateCa() {
        startHttps()
        assertTrue(call(TlsPolicy.systemDefault()).exceptionOrNull() is SSLException)
    }

    @Test
    fun acceptsAMatchingPinAndRejectsAMismatch() {
        startHttps()
        val trust = TlsPolicy.customTrust(listOf(ca.certificate))
        assertEquals("ok", call(trust.withPinnedCertificates("localhost", listOf(leaf.certificate))).getOrThrow().body)
        val wrong = trust.withPins("localhost", CertificatePinner.pin(otherCa.certificate))
        assertTrue(call(wrong).exceptionOrNull() is SSLException)
    }

    @Test
    fun presentsAClientCertificateForMutualTls() {
        startHttps(requireClientAuth = true)
        val client = HeldCertificate.Builder().signedBy(ca).commonName("client").build()
        val trust = TlsPolicy.customTrust(listOf(ca.certificate))
        assertTrue(call(trust).isFailure)
        val keyManager = HandshakeCertificates.Builder().heldCertificate(client, ca.certificate).build().keyManager
        assertEquals("ok", call(trust.withClientCertificate(keyManager)).getOrThrow().body)
    }

    @Test
    fun validatesItsArguments() {
        assertThrows<IllegalArgumentException> { TlsPolicy.customTrust(emptyList()) }
        assertThrows<IllegalArgumentException> { TlsPolicy.pinned("example.com") }
        assertThrows<IllegalArgumentException> { TlsPolicy.pinned("example.com", "md5/abc") }
        assertEquals(1, TlsPolicy.certificates(ca.certificatePem().encodeToByteArray()).size)
    }
}
