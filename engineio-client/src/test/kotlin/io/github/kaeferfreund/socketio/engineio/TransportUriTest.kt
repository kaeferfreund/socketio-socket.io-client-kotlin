package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ports of `Transport > transport uris` (`test/transport.js`) and of the URI
 * parsing cases of `test/engine.io-client.js`.
 */
class TransportUriTest {
    private fun options(
        executor: ProtocolExecutor,
        hostname: String,
        secure: Boolean = false,
        port: String = "",
        query: Map<String, String> = emptyMap(),
        timestampRequests: Boolean? = false,
        timestampParam: String = "t",
    ) = TransportOptions(
        hostname = hostname,
        secure = secure,
        port = port,
        path = "/engine.io",
        query = query,
        timestampRequests = timestampRequests,
        timestampParam = timestampParam,
        forceBase64 = false,
        extraHeaders = emptyMap(),
        requestTimeout = null,
        withCredentials = false,
        protocols = emptyList(),
        perMessageDeflateThreshold = 1024,
        maxPollingResponseBytes = Long.MAX_VALUE,
        executor = executor,
        cookieJar = null,
        httpClient = NoHttp,
        webSocketClient = NoWebSocket,
        logger = SocketLogger.NONE,
    )

    private object NoHttp : EngineHttpClient {
        override fun execute(
            request: EngineHttpRequest,
            callback: EngineHttpCallback,
        ): Cancellable = error("not used")
    }

    private object NoWebSocket : EngineWebSocketClient {
        override fun connect(
            request: EngineWebSocketRequest,
            listener: EngineWebSocketListener,
        ): EngineWebSocketConnection = error("not used")
    }

    private fun polling(
        hostname: String,
        secure: Boolean = false,
        port: String = "",
        query: Map<String, String> = emptyMap(),
        timestampRequests: Boolean? = false,
    ) = runTestExecutor { PollingTransport(options(it, hostname, secure, port, query, timestampRequests)).uri() }

    private fun websocket(
        hostname: String,
        secure: Boolean = false,
        port: String = "",
        query: Map<String, String> = emptyMap(),
        timestampRequests: Boolean? = false,
        timestampParam: String = "t",
    ) = runTestExecutor { WebSocketTransport(options(it, hostname, secure, port, query, timestampRequests, timestampParam)).uri() }

    private fun runTestExecutor(block: (ProtocolExecutor) -> String): String {
        var result = ""
        runTest { result = block(ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource)) }
        return result
    }

    // JS-203
    @Test
    fun generatesAnHttpUri() {
        assertTrue(polling("localhost", query = mapOf("sid" to "test")).contains("http://localhost/engine.io?sid=test"))
    }

    // JS-204
    @Test
    fun generatesAnHttpUriWithoutPort80() {
        assertTrue(polling("localhost", port = "80", query = mapOf("sid" to "test")).contains("http://localhost/engine.io?sid=test"))
    }

    // JS-205
    @Test
    fun generatesAnHttpUriWithAPort() {
        assertTrue(polling("localhost", port = "3000", query = mapOf("sid" to "test")).contains("http://localhost:3000/engine.io?sid=test"))
    }

    // JS-206, JS-207 (JavaScript accepts the port as number or string; Kotlin keeps it as the string form)
    @Test
    fun generatesAnHttpsUriWithoutPort443() {
        assertTrue(polling("localhost", secure = true, port = "443", query = mapOf("sid" to "test")).contains("https://localhost/engine.io?sid=test"))
    }

    // JS-208
    @Test
    fun generatesAnHttpsUriWithAPort() {
        assertTrue(
            polling("localhost", secure = true, port = "8443", query = mapOf("sid" to "test")).contains("https://localhost:8443/engine.io?sid=test"),
        )
    }

    // JS-209
    @Test
    fun generatesATimestampedUri() {
        val uri = polling("localhost", timestampRequests = true)
        assertTrue(Regex("http://localhost/engine\\.io\\?(j=[0-9]+&)?(t=[0-9A-Za-z-_]+)").containsMatchIn(uri), uri)
        // Polling timestamps by default (`timestampRequests !== false`).
        assertTrue(polling("localhost", timestampRequests = null).contains("?t="))
    }

    // JS-210
    @Test
    fun generatesAnIpv6Uri() {
        assertTrue(polling("::1", port = "80").contains("http://[::1]/engine.io"))
    }

    // JS-211
    @Test
    fun generatesAnIpv6UriWithPort() {
        assertTrue(polling("::1", port = "8080").contains("http://[::1]:8080/engine.io"))
    }

    // JS-212
    @Test
    fun generatesAWsUri() {
        assertEquals("ws://test/engine.io?transport=websocket", websocket("test", query = mapOf("transport" to "websocket")))
    }

    // JS-213
    @Test
    fun generatesAWssUri() {
        assertEquals("wss://test/engine.io", websocket("test", secure = true))
    }

    // JS-214
    @Test
    fun timestampsWsUris() {
        val uri = websocket("localhost", timestampRequests = true, timestampParam = "woot")
        assertTrue(Regex("ws://localhost/engine\\.io\\?woot=[0-9A-Za-z-_]+").containsMatchIn(uri), uri)
        // WebSocket does not timestamp by default.
        assertEquals("ws://localhost/engine.io", websocket("localhost", timestampRequests = null))
    }

    // JS-215
    @Test
    fun generatesAWsIpv6Uri() {
        assertEquals("ws://[::1]/engine.io", websocket("::1", port = "80"))
    }

    // JS-216
    @Test
    fun generatesAWsIpv6UriWithPort() {
        assertEquals("ws://[::1]:8080/engine.io", websocket("::1", port = "8080"))
    }

    // JS-159
    @Test
    fun parsesAnHttpUriWithoutPort() =
        runTest {
            val engine = newEngine("http://localhost")
            assertEquals("localhost", engine.hostname)
            assertEquals("80", engine.port)
        }

    // JS-160
    @Test
    fun parsesAnHttpsUriWithoutPort() =
        runTest {
            val engine = newEngine("https://localhost")
            assertEquals("localhost", engine.hostname)
            assertEquals("443", engine.port)
        }

    // JS-161
    @Test
    fun parsesAWssUriWithoutPort() =
        runTest {
            val engine = newEngine("wss://localhost")
            assertEquals("localhost", engine.hostname)
            assertEquals("443", engine.port)
        }

    // JS-162
    @Test
    fun parsesAWssUriWithPort() =
        runTest {
            val engine = newEngine("wss://localhost:2020")
            assertEquals("localhost", engine.hostname)
            assertEquals("2020", engine.port)
        }

    // JS-165
    @Test
    fun handlesTheAddTrailingSlashOption() =
        runTest {
            val requests = ArrayList<String>()
            val capture =
                object : EngineHttpClient {
                    override fun execute(
                        request: EngineHttpRequest,
                        callback: EngineHttpCallback,
                    ): Cancellable {
                        requests.add(request.url)
                        return Cancellable.NONE
                    }
                }
            val executor = ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
            val engine =
                EngineSocket(
                    "http://localhost",
                    EngineOptions(addTrailingSlash = false, transports = listOf("polling"), clients = EngineClients(capture, null)),
                    executor,
                )
            executor.execute { engine.open() }
            testScheduler.runCurrent()
            assertTrue(requests.single().startsWith("http://localhost/engine.io?"), requests.single())
            executor.shutdown()
        }

    // JS-166
    @Test
    fun parsesAnIpv6UriWithoutPort() =
        runTest {
            val engine = newEngine("http://[::1]")
            assertEquals("::1", engine.hostname)
            assertEquals("80", engine.port)
        }

    // JS-167
    @Test
    fun parsesAnIpv6UriWithPort() =
        runTest {
            val engine = newEngine("http://[::1]:8080")
            assertEquals("::1", engine.hostname)
            assertEquals("8080", engine.port)
        }

    @Test
    fun encodesQueryComponentsLikeEncodeUriComponent() {
        assertEquals("a%20b=%E2%82%AC%26%3D%2F%3F!'()*~", EngineUri.encodeQuery(mapOf("a b" to "€&=/?!'()*~")))
        assertEquals(mapOf("a b" to "€&", "c" to "undefined"), EngineUri.decodeQuery("a%20b=%E2%82%AC%26&c"))
    }

    @Test
    fun randomStringIsUrlSafe() {
        repeat(100) { assertTrue(Regex("[0-9a-z]+").matches(EngineUri.randomString())) }
    }

    private fun kotlinx.coroutines.test.TestScope.newEngine(uri: String): EngineSocket =
        EngineSocket(uri, EngineOptions(), ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource))
}
