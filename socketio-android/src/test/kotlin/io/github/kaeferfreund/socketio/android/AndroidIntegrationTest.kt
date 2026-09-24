package io.github.kaeferfreund.socketio.android

import android.app.Application
import android.content.ComponentCallbacks2
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.kaeferfreund.socketio.DisconnectReason
import io.github.kaeferfreund.socketio.ManagerEvent
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.engineio.LogLevel
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import io.github.kaeferfreund.socketio.testing.FakeSocketIOServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowSystemClock
import java.io.StringReader
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The Android integration under Robolectric: network callbacks, lifecycle, logging and adapters. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidIntegrationTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val managers = ArrayList<SocketManager>()

    /** Always closes the managers, so a failed assertion cannot leave reconnect timers running on the test scheduler. */
    private fun guarded(block: suspend TestScope.() -> Unit) =
        runTest {
            try {
                block()
            } finally {
                managers.forEach(SocketManager::close)
                managers.clear()
                runCurrent()
            }
        }

    private fun TestScope.manager(
        server: FakeSocketIOServer,
        android: AndroidSocketOptions.() -> Unit = {},
        setup: SocketManager.() -> Unit = {},
    ): SocketManager =
        SocketManager(
            "http://fake.test",
            SocketManagerOptions {
                android(context) {
                    mainThreadCallbacks = false
                    android()
                }
                // After android(): the in-memory server replaces the OkHttp stack, and the
                // test scheduler replaces the elapsed-realtime clock.
                clients = server.clients
                dispatcher = StandardTestDispatcher(testScheduler)
                timeSource = testScheduler.timeSource
                reconnectionDelay = 5.seconds
                randomizationFactor = 0.0
            },
            setup,
        ).also { managers += it }

    private fun connectivity(): ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private fun networkCallbacks() = shadowOf(connectivity()).networkCallbacks

    @Test
    fun losingTheNetworkClosesAtOnceAndItsReturnReconnectsWithoutBackoff() =
        guarded {
            val server = FakeSocketIOServer(backgroundScope)
            val reasons = ArrayList<DisconnectReason>()
            val manager = manager(server)
            val socket = manager.socket("/") { onDisconnect { reason, _ -> reasons += reason } }
            runCurrent()
            assertTrue(socket.connected)
            val callback = networkCallbacks().single()
            val active = connectivity().activeNetwork!!
            callback.onLost(active)
            runCurrent()
            assertEquals(listOf(DisconnectReason.TRANSPORT_CLOSE), reasons)
            assertEquals(false, manager.networkStatus!!.value.available)
            // Offline: the backoff delay passes without an attempt.
            advanceTimeBy(30.seconds)
            assertFalse(socket.connected)
            assertEquals(1, server.engine.sessions.size)
            // Back online: reconnect immediately.
            callback.onAvailable(active)
            runCurrent()
            assertTrue(socket.connected)
            assertEquals(2, server.engine.sessions.size)
            manager.close()
            runCurrent()
            assertTrue(networkCallbacks().isEmpty())
        }

    @Test
    fun aNewDefaultNetworkMovesTheConnection() =
        guarded {
            val server = FakeSocketIOServer(backgroundScope)
            val manager = manager(server)
            val socket = manager.socket("/")
            runCurrent()
            val callback = networkCallbacks().single()
            callback.onAvailable(ShadowNetwork.newInstance(4711))
            runCurrent()
            // Dropped on the old network and reconnected at once on the new one.
            assertTrue(socket.connected)
            assertEquals(2, server.engine.sessions.size)
            assertFalse(server.engine.sessions.values.first().isOpen)
            manager.close()
            runCurrent()
        }

    @Test
    fun theBackgroundPolicyPausesAndResumesTheConnection() =
        guarded {
            val server = FakeSocketIOServer(backgroundScope)
            val lifecycle = TestLifecycleOwner(Lifecycle.State.RESUMED)
            val manager =
                SocketManager(
                    "http://fake.test",
                    SocketManagerOptions {
                        clients = server.clients
                        dispatcher = StandardTestDispatcher(testScheduler)
                        timeSource = testScheduler.timeSource
                        plugins += BackgroundPolicyPlugin(BackgroundPolicy.DisconnectAfter(30.seconds)) { lifecycle }
                    },
                ).also { managers += it }
            val socket = manager.socket("/")
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(socket.connected)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
            runCurrent()
            assertTrue(socket.connected)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            runCurrent()
            assertFalse(socket.connected)
            assertTrue(manager.isPaused)
            // No reconnection while paused.
            advanceTimeBy(60.seconds)
            assertFalse(socket.connected)
            assertTrue(socket.active)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            runCurrent()
            assertTrue(socket.connected)
            manager.close()
            runCurrent()
        }

    @Test
    fun comingBackBeforeTheDelayKeepsTheConnection() =
        guarded {
            val server = FakeSocketIOServer(backgroundScope)
            val lifecycle = TestLifecycleOwner(Lifecycle.State.RESUMED)
            val manager =
                SocketManager(
                    "http://fake.test",
                    SocketManagerOptions {
                        clients = server.clients
                        dispatcher = StandardTestDispatcher(testScheduler)
                        timeSource = testScheduler.timeSource
                        plugins += BackgroundPolicyPlugin(BackgroundPolicy.DisconnectAfter(30.seconds)) { lifecycle }
                    },
                ).also { managers += it }
            val socket = manager.socket("/")
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            runCurrent()
            assertTrue(socket.connected)
            assertEquals(1, server.engine.sessions.size)
            manager.close()
            runCurrent()
        }

    @Test
    fun theLoggerIsGatedByIsLoggable() {
        ShadowLog.clear()
        val logger = AndroidLogger("SocketIOTest")
        ShadowLog.setLoggable("SocketIOTest", Log.WARN)
        assertFalse(logger.isLoggable(LogLevel.DEBUG))
        assertTrue(logger.isLoggable(LogLevel.ERROR))
        logger.log(LogLevel.ERROR, "engine", "boom")
        val entry = ShadowLog.getLogsForTag("SocketIOTest").single()
        assertEquals(Log.ERROR, entry.type)
        assertEquals("[engine] boom", entry.msg)
    }

    @Test
    fun theElapsedRealtimeClockKeepsCountingInSleep() {
        val mark = ElapsedRealtimeTimeSource.markNow()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        assertEquals(5 * 60, mark.elapsedNow().inWholeSeconds)
    }

    @Test
    fun convertsBetweenOrgJsonAndSocketIOValues() {
        val json = JSONObject("{\"a\":1,\"b\":[true,null,\"x\",2.5],\"c\":{\"d\":\"e\"}}")
        val value = json.toSocketIOValue()
        assertEquals(SocketIOValue.of(mapOf("a" to 1, "b" to listOf(true, null, "x", 2.5), "c" to mapOf("d" to "e"))), value)
        assertEquals(json.toString(), value.toJSONObject().toString())
        assertEquals(SocketIOValue.arrayOf(1, "two"), JSONArray("[1,\"two\"]").toSocketIOValue())
    }

    @Test
    fun streamsLargeAndDeepJsonWithJsonReader() {
        val depth = 10_000
        val text = "[".repeat(depth) + "\"x\"" + "]".repeat(depth)
        var value = readSocketIOValue(StringReader(text))
        repeat(depth) { value = value.array!!.single() }
        assertEquals("x", value.string)
        assertEquals(SocketIOValue.of(mapOf("n" to 12345678901L, "d" to 0.5)), readSocketIOValue(StringReader("{\"n\":12345678901,\"d\":0.5}")))
    }

    @Test
    fun savesAndRestoresTheConfiguration() {
        val config = SavedSocketConfig("https://example.com", "/io", listOf("websocket"), mapOf("v" to "3"), mapOf("X-App" to "android"), listOf("/", "/chat"))
        val restored = SavedSocketConfig.fromBundle(Bundle(config.toBundle()))!!
        assertEquals(config.uri, restored.uri)
        assertEquals(config.path, restored.path)
        assertEquals(config.transports, restored.transports)
        assertEquals(config.query, restored.query)
        assertEquals(config.extraHeaders, restored.extraHeaders)
        assertEquals(config.namespaces, restored.namespaces)
        assertNull(SavedSocketConfig.fromBundle(Bundle()))
        val options = SocketManagerOptions { restored.applyTo(this) }
        assertEquals("/io", options.engine.path)
    }

    @Test
    fun trimMemoryReleasesIdleConnections() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        try {
            server.enqueue(mockwebserver3.MockResponse.Builder().body("ok").build())
            val client = okhttp3.OkHttpClient()
            client.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).execute().use { it.body.string() }
            assertEquals(1, client.connectionPool.idleConnectionCount())
            val manager = SocketManager("http://fake.test", SocketManagerOptions { autoConnect = false })
            val handle = TrimMemoryPlugin(context, client).attach(manager)
            @Suppress("DEPRECATION")
            context.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            assertEquals(0, client.connectionPool.idleConnectionCount())
            handle.cancel()
            manager.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun theAndroidOptionsConfigureTheManager() {
        val options =
            SocketManagerOptions {
                android(context) {
                    tracing = true
                    trafficStatsTag = 0x5001
                    backgroundPolicy = BackgroundPolicy.DisconnectImmediately
                }
            }
        assertEquals(ElapsedRealtimeTimeSource, options.timeSource)
        assertNotNull(options.callbackDispatcher)
        assertTrue(options.logger is AndroidLogger)
        assertEquals(AndroidTracer, options.tracer)
        assertEquals(3, options.plugins.size)
        assertTrue(options.engine.clients?.http is io.github.kaeferfreund.socketio.okhttp.OkHttpEngineClients)
    }

    @Test
    fun theKeyChainKeyManagerOnlyServesItsAlias() {
        val keyManager = KeyChainKeyManager(context, "client")
        assertEquals("client", keyManager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertEquals(listOf("client"), keyManager.getClientAliases("RSA", null).toList())
        assertNull(keyManager.getCertificateChain("other"))
        assertNull(keyManager.getPrivateKey("other"))
        assertNull(keyManager.getServerAliases("RSA", null))
        TimeUnit.MILLISECONDS.hashCode()
        ManagerEvent.Open.hashCode()
        1.milliseconds.hashCode()
    }
}
