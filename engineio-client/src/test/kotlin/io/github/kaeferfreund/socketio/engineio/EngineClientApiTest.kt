package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketOptions
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Modifier

/**
 * Ports of `packages/engine.io-client/test/engine.io-client.js`,
 * `parseuri.js` and the non-network cases of `transport.js`.
 */
class EngineClientApiTest {
    private fun TestScope.engine(options: EngineOptions): EngineSocket =
        EngineSocket(null, options, ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource))

    // JS-158
    @Test
    fun exposesTheProtocolNumber() {
        assertEquals(4, EngineSocket.PROTOCOL)
    }

    // JS-163
    @Test
    fun parsesAHostWithoutPort() =
        runTest {
            val client = engine(EngineOptions(host = "localhost"))
            assertEquals("localhost", client.hostname)
            assertEquals("80", client.port)
        }

    // JS-164
    @Test
    fun parsesAHostWithPort() =
        runTest {
            val client = engine(EngineOptions(host = "localhost", port = "8080"))
            assertEquals("localhost", client.hostname)
            assertEquals("8080", client.port)
        }

    // JS-168
    @Test
    fun parsesABracketedIpv6HostWithoutPort() =
        runTest {
            val client = engine(EngineOptions(host = "[::1]"))
            assertEquals("::1", client.hostname)
            assertEquals("80", client.port)
        }

    // JS-169
    @Test
    fun parsesASecureBracketedIpv6HostWithoutPort() =
        runTest {
            val client = engine(EngineOptions(secure = true, host = "[::1]"))
            assertEquals("::1", client.hostname)
            assertEquals("443", client.port)
        }

    // JS-170
    @Test
    fun parsesABracketedIpv6HostWithPort() =
        runTest {
            val client = engine(EngineOptions(host = "[::1]", port = "8080"))
            assertEquals("::1", client.hostname)
            assertEquals("8080", client.port)
        }

    // JS-171
    @Test
    fun parsesAnIpv6HostWithoutBrackets() =
        runTest {
            val client = engine(EngineOptions(host = "::1"))
            assertEquals("::1", client.hostname)
            assertEquals("80", client.port)
        }

    // JS-172
    @Test
    fun generatesARandomString() {
        val a = EngineUri.randomString()
        val b = EngineUri.randomString()
        val c = EngineUri.randomString()
        assertEquals(8, a.length)
        assertNotEquals(a, b)
        assertNotEquals(b, c)
    }

    // JS-183
    @Test
    fun parsesAUri() {
        val http = EngineUri.parse("http://google.com")
        val https = EngineUri.parse("https://www.google.com:80")
        val query = EngineUri.parse("google.com:8080/foo/bar?foo=bar")
        val localhost = EngineUri.parse("localhost:8080")
        val ipv6 = EngineUri.parse("2001:0db8:85a3:0042:1000:8a2e:0370:7334")
        val ipv6short = EngineUri.parse("2001:db8:85a3:42:1000:8a2e:370:7334")
        val ipv6port = EngineUri.parse("2001:db8:85a3:42:1000:8a2e:370:7334:80")
        val ipv6abbrev = EngineUri.parse("2001::7334:a:80")
        val ipv6http = EngineUri.parse("http://[2001::7334:a]:80")
        val ipv6query = EngineUri.parse("http://[2001::7334:a]:80/foo/bar?foo=bar")

        assertEquals("http", http.protocol)
        assertEquals("", http.port)
        assertEquals("google.com", http.host)
        assertEquals("https", https.protocol)
        assertEquals("80", https.port)
        assertEquals("www.google.com", https.host)
        assertEquals("8080", query.port)
        assertEquals("foo=bar", query.query)
        assertEquals("/foo/bar", query.path)
        assertEquals("/foo/bar?foo=bar", query.relative)
        assertEquals("bar", query.queryKey["foo"])
        assertEquals("foo", query.pathNames[0])
        assertEquals("bar", query.pathNames[1])
        assertEquals("", localhost.protocol)
        assertEquals("localhost", localhost.host)
        assertEquals("8080", localhost.port)
        assertEquals("", ipv6.protocol)
        assertEquals("2001:0db8:85a3:0042:1000:8a2e:0370:7334", ipv6.host)
        assertEquals("", ipv6.port)
        assertEquals("", ipv6short.protocol)
        assertEquals("2001:db8:85a3:42:1000:8a2e:370:7334", ipv6short.host)
        assertEquals("", ipv6short.port)
        assertEquals("", ipv6port.protocol)
        assertEquals("2001:db8:85a3:42:1000:8a2e:370:7334", ipv6port.host)
        assertEquals("80", ipv6port.port)
        assertEquals("", ipv6abbrev.protocol)
        assertEquals("2001::7334:a:80", ipv6abbrev.host)
        assertEquals("", ipv6abbrev.port)
        assertEquals("http", ipv6http.protocol)
        assertEquals("80", ipv6http.port)
        assertEquals("2001::7334:a", ipv6http.host)
        assertEquals("http", ipv6query.protocol)
        assertEquals("80", ipv6query.port)
        assertEquals("2001::7334:a", ipv6query.host)
        assertEquals("/foo/bar?foo=bar", ipv6query.relative)

        val withUserInfo = EngineUri.parse("ws://foo:bar@google.com")
        assertEquals("ws", withUserInfo.protocol)
        assertEquals("foo:bar", withUserInfo.userInfo)
        assertEquals("foo", withUserInfo.user)
        assertEquals("bar", withUserInfo.password)
        assertEquals("google.com", withUserInfo.host)

        val relativeWithQuery = EngineUri.parse("/foo?bar=@example.com")
        assertEquals("", relativeWithQuery.host)
        assertEquals("/foo", relativeWithQuery.path)
        assertEquals("bar=@example.com", relativeWithQuery.query)

        assertEquals("URI too long", assertThrows<IllegalArgumentException> { EngineUri.parse("a".repeat(8001)) }.message)
    }

    // JS-201
    @Test
    fun exposesTheTransportBaseClass() {
        assertTrue(Modifier.isPublic(EngineTransport::class.java.modifiers))
        assertTrue(Modifier.isAbstract(EngineTransport::class.java.modifiers))
    }

    // JS-202
    @Test
    fun exposesThePollingAndWebSocketTransports() =
        runTest {
            val executor = ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
            val options = transportOptions(executor, RecordingWebSocketClient())
            assertEquals("polling", PollingTransport.FACTORY.create(options).name)
            assertEquals("websocket", WebSocketTransport.FACTORY.create(options).name)
        }

    // JS-221
    @Test
    fun aZeroThresholdCompressesEveryMessage() =
        runTest {
            val ws = RecordingWebSocketClient()
            sendHi(ws, threshold = 0)
            assertEquals(listOf(true), ws.compressFlags)
        }

    // JS-222
    @Test
    fun doesNotCompressBelowTheThreshold() =
        runTest {
            val ws = RecordingWebSocketClient()
            // The default threshold is 1024 bytes; "4hi" is three.
            sendHi(ws, threshold = EngineOptions().perMessageDeflateThreshold)
            assertEquals(listOf(false), ws.compressFlags)
        }

    private fun TestScope.sendHi(
        ws: RecordingWebSocketClient,
        threshold: Int?,
    ) {
        val executor = ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
        val transport = WebSocketTransport(transportOptions(executor, ws, threshold))
        executor.execute { transport.open() }
        runCurrent()
        ws.listener!!.onOpen(emptyList())
        runCurrent()
        executor.execute {
            transport.send(
                listOf(
                    EngineIOPacket(
                        EngineIOPacketType.MESSAGE,
                        io.github.kaeferfreund.socketio.engineio.parser.EngineIOData.Text("hi"),
                        EngineIOPacketOptions(compress = true),
                    ),
                ),
            )
        }
        runCurrent()
        executor.shutdown()
    }

    private fun transportOptions(
        executor: ProtocolExecutor,
        ws: EngineWebSocketClient,
        threshold: Int? = 1024,
    ) = TransportOptions(
        hostname = "localhost",
        secure = false,
        port = "80",
        path = "/engine.io/",
        query = mapOf("EIO" to "4"),
        timestampRequests = null,
        timestampParam = "t",
        forceBase64 = false,
        extraHeaders = emptyMap(),
        requestTimeout = null,
        withCredentials = false,
        protocols = emptyList(),
        perMessageDeflateThreshold = threshold,
        maxPollingResponseBytes = Long.MAX_VALUE,
        executor = executor,
        cookieJar = null,
        httpClient =
        object : EngineHttpClient {
            override fun execute(
                request: EngineHttpRequest,
                callback: EngineHttpCallback,
            ): Cancellable = Cancellable.NONE
        },
        webSocketClient = ws,
        logger = SocketLogger.NONE,
    )

    /** Records what the transport hands to the WebSocket, like the stubbed `ws.send` of the JavaScript test. */
    private class RecordingWebSocketClient : EngineWebSocketClient {
        var listener: EngineWebSocketListener? = null
        val compressFlags = ArrayList<Boolean>()

        override fun connect(
            request: EngineWebSocketRequest,
            listener: EngineWebSocketListener,
        ): EngineWebSocketConnection {
            this.listener = listener
            return object : EngineWebSocketConnection {
                override fun send(
                    text: String,
                    compress: Boolean,
                ): Boolean {
                    compressFlags += compress
                    return true
                }

                override fun send(
                    bytes: ByteArray,
                    compress: Boolean,
                ): Boolean {
                    compressFlags += compress
                    return true
                }

                override val queuedBytes: Long get() = 0

                override fun close(
                    code: Int,
                    reason: String?,
                ) = Unit

                override fun cancel() = Unit
            }
        }
    }
}
