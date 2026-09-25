package io.github.kaeferfreund.socketio.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import io.github.kaeferfreund.socketio.ManagerEvent
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.engineio.EngineHttpCallback
import io.github.kaeferfreund.socketio.engineio.EngineHttpRequest
import io.github.kaeferfreund.socketio.engineio.EngineHttpResponse
import io.github.kaeferfreund.socketio.okhttp.OkHttpEngineClients
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import io.github.kaeferfreund.socketio.testing.FakeSocketIOServer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowTrace
import java.io.IOException
import java.io.StringReader
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.time.Duration.Companion.seconds

/** The Android stack beyond the lifecycle: starting offline, network quality, the real OkHttp path, tracing and JSON streaming. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidPlatformTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun connectivity(): ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

    // An app started in airplane mode must not burn its reconnection attempts before a network exists.
    @Test
    fun startingOfflineWaitsForANetworkInsteadOfSpendingAttempts() =
        runTest {
            shadowOf(connectivity()).setActiveNetworkInfo(null)
            val server = FakeSocketIOServer(backgroundScope)
            // Without a network every request fails, as a DNS lookup would.
            server.engine.allowRequest = { 503 }
            var attempts = 0
            val manager =
                SocketManager(
                    "http://fake.test",
                    SocketManagerOptions {
                        android(context) { mainThreadCallbacks = false }
                        clients = server.clients
                        dispatcher = StandardTestDispatcher(testScheduler)
                        timeSource = testScheduler.timeSource
                        reconnectionAttempts = 3
                    },
                ) { on<ManagerEvent.ReconnectAttempt> { attempts++ } }
            try {
                val status = manager.networkStatus!!
                assertFalse(status.value.available)
                val socket = manager.socket("/")
                runCurrent()
                // The first open fails; the reconnection loop then waits for a network.
                advanceTimeBy(120.seconds)
                assertEquals(0, attempts)
                assertFalse(socket.connected)
                server.engine.allowRequest = { null }
                val callback = shadowOf(connectivity()).networkCallbacks.single()
                callback.onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(7))
                runCurrent()
                assertTrue(status.value.available)
                assertTrue(socket.connected)
            } finally {
                manager.close()
                runCurrent()
            }
        }

    // Apps read networkStatus to hold back large uploads on metered or unvalidated networks.
    @Test
    fun capabilityChangesOfTheCurrentNetworkUpdateTheStatus() =
        runTest {
            val server = FakeSocketIOServer(backgroundScope)
            val manager =
                SocketManager(
                    "http://fake.test",
                    SocketManagerOptions {
                        android(context) { mainThreadCallbacks = false }
                        clients = server.clients
                        dispatcher = StandardTestDispatcher(testScheduler)
                        timeSource = testScheduler.timeSource
                        autoConnect = false
                    },
                )
            try {
                val callback = shadowOf(connectivity()).networkCallbacks.single()
                val active = connectivity().activeNetwork!!
                val validatedWifi =
                    ShadowNetworkCapabilities.newInstance().also {
                        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    }
                callback.onCapabilitiesChanged(active, validatedWifi)
                assertEquals("NetworkStatus(available=true, metered=false, validated=true)", manager.networkStatus!!.value.toString())
                val mobile = ShadowNetworkCapabilities.newInstance()
                callback.onCapabilitiesChanged(active, mobile)
                val status = manager.networkStatus!!.value
                assertTrue(status.metered)
                assertFalse(status.validated)
                // Another network's capabilities do not describe the connection.
                callback.onCapabilitiesChanged(org.robolectric.shadows.ShadowNetwork.newInstance(99), validatedWifi)
                assertTrue(manager.networkStatus!!.value.metered)
            } finally {
                manager.close()
            }
        }

    @Test
    fun configureOkHttpIsAppliedOnTopOfTheNetworkBinding() {
        val custom = Dns { listOf(InetAddress.getLoopbackAddress()) }
        val bound = SocketManagerOptions { android(context) { configureOkHttp { dns(custom) } } }
        assertSame(custom, (bound.engine.clients!!.http as OkHttpEngineClients).client.dns)
        val unbound =
            SocketManagerOptions {
                android(context) {
                    bindToActiveNetwork = false
                    okHttpClient = OkHttpClient.Builder().dns(custom).build()
                }
            }
        val client = (unbound.engine.clients!!.http as OkHttpEngineClients).client
        assertSame(custom, client.dns)
        assertSame(SocketFactory.getDefault(), client.socketFactory)
    }

    @Test
    fun readingJsonRejectsDataAfterTheValue() {
        assertThrows(IOException::class.java) { readSocketIOValue(StringReader("{\"a\":1} garbage")) }
        assertThrows(IOException::class.java) { readSocketIOValue(StringReader("[1][2]")) }
        assertEquals(SocketIOValue.arrayOf(1), readSocketIOValue(StringReader(" [1] ")))
    }

    // The whole Android OkHttp stack: network-bound socket factory and DNS, traffic tag, and
    // configureOkHttp on top, against a real local HTTP server.
    @Test
    fun theAndroidStackCarriesRealRequestsWithItsCustomizations() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("ok").build())
            val seen = ArrayList<String>()
            val options =
                SocketManagerOptions {
                    android(context) {
                        trafficStatsTag = 0x5001
                        configureOkHttp {
                            addInterceptor { chain ->
                                seen += chain.request().url.encodedPath
                                chain.proceed(chain.request())
                            }
                        }
                    }
                }
            val future = CompletableFuture<Result<EngineHttpResponse>>()
            options.engine.clients!!.http!!.execute(
                EngineHttpRequest("GET", server.url("/socket.io/").toString(), emptyList(), null, null),
                object : EngineHttpCallback {
                    override fun onResponse(response: EngineHttpResponse) {
                        future.complete(Result.success(response))
                    }

                    override fun onFailure(error: Throwable) {
                        future.complete(Result.failure(error))
                    }
                },
            )
            assertEquals("ok", future.get(10, TimeUnit.SECONDS).getOrThrow().body)
            assertEquals(listOf("/socket.io/"), seen)
        } finally {
            server.close()
        }
    }

    // Section names include the event name ("socket.io ack <event>"), which the app controls.
    // android.os.Trace rejects names over 127 characters with an exception; the tracer (through
    // androidx.tracing, which also shortens labels) must never pass one on.
    @Test
    fun theTracerShortensSectionNamesThatTraceWouldReject() {
        ShadowTrace.setEnabled(true)
        val longName = "socket.io ack " + "x".repeat(300)
        assertThrows(IllegalArgumentException::class.java) { android.os.Trace.beginAsyncSection(longName, 1) }
        AndroidTracer.beginAsyncSection(longName, 2)
        AndroidTracer.endAsyncSection(longName, 2)
    }

    // Booleans, null and non-integer numbers from the streaming reader keep their JSON types.
    @Test
    fun theStreamingReaderKeepsEveryJsonType() {
        val value = readSocketIOValue(StringReader("{\"t\":true,\"f\":false,\"n\":null,\"e\":1e3,\"neg\":-2,\"s\":\"\"}"))
        assertEquals(SocketIOValue.of(mapOf("t" to true, "f" to false, "n" to null, "e" to 1000.0, "neg" to -2, "s" to "")), value)
    }
}
