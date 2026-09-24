package io.github.kaeferfreund.socketio.android

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kaeferfreund.socketio.ConnectionState
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs on an emulator or device against the Node fixture `fixtures/server.js`
 * on the host (10.0.2.2), through the real OkHttp stack, ConnectivityManager
 * callbacks, network binding and main-thread callbacks. CI passes the fixture
 * port as the instrumentation argument `fixturePort`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val managers = ArrayList<SocketManager>()

    private val fixtureUrl: String
        get() {
            val port =
                requireNotNull(InstrumentationRegistry.getArguments().getString("fixturePort")) {
                    "start fixtures/server.js on the host and pass -Pandroid.testInstrumentationRunnerArguments.fixturePort=<port>"
                }
            return "http://10.0.2.2:$port"
        }

    private fun manager(
        android: AndroidSocketOptions.() -> Unit = {},
        options: SocketManagerOptions.Builder.() -> Unit = {},
    ): SocketManager =
        SocketManager(
            fixtureUrl,
            SocketManagerOptions {
                android(context, android)
                options()
            },
        ).also { managers += it }

    @After
    fun closeManagers() {
        managers.forEach(SocketManager::close)
    }

    @Test
    fun connectsUpgradesAndAcknowledgesThroughOkHttp() =
        runBlocking {
            val manager = manager()
            val socket = manager.socket("/")
            withTimeout(20.seconds) {
                socket.state.first { it is ConnectionState.Connected }
                manager.transportName.first { it == Transport.WEBSOCKET }
            }
            assertEquals("from the device", withTimeout(10.seconds) { socket.emitWithAck("echo", "from the device") }[0].string)
            val binary = withTimeout(10.seconds) { socket.emitWithAck("parity-binary", byteArrayOf(1, 2, 3)) }
            assertTrue(byteArrayOf(1, 2, 3).contentEquals(binary[0].bytes))
        }

    @Test
    fun pollingOnlyWorksThroughTheBoundNetwork() =
        runBlocking {
            val manager = manager(options = { transports = listOf(Transport.POLLING) })
            val socket = manager.socket("/")
            withTimeout(20.seconds) { socket.state.first { it is ConnectionState.Connected } }
            assertEquals(Transport.POLLING, manager.transportName.value)
            assertEquals("polling", withTimeout(10.seconds) { socket.emitWithAck("echo", "polling") }[0].string)
            assertTrue(manager.networkStatus!!.value.available)
        }

    @Test
    fun listenersRunOnTheMainThreadByDefault() =
        runBlocking {
            val onMain = CompletableDeferred<Boolean>()
            manager().socket("/") { onConnect { onMain.complete(Looper.myLooper() == Looper.getMainLooper()) } }
            assertTrue(withTimeout(20.seconds) { onMain.await() })
        }

    @Test
    fun theElapsedRealtimeClockFollowsSystemClock() {
        val mark = ElapsedRealtimeTimeSource.markNow()
        val before = SystemClock.elapsedRealtimeNanos()
        SystemClock.sleep(50)
        val elapsed = mark.elapsedNow()
        val actual = (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000
        assertTrue("elapsed $elapsed", elapsed >= 50.milliseconds)
        assertTrue("elapsed $elapsed, system $actual ms", elapsed.inWholeMilliseconds <= actual + 5)
    }

    @Test
    fun theSavedConfigurationSurvivesParceling() {
        val config =
            SavedSocketConfig(fixtureUrl, "/socket.io", listOf(Transport.WEBSOCKET), mapOf("v" to "1"), mapOf("X-App" to "device"), listOf("/", "/chat"))
        val parcel = Parcel.obtain()
        val restored =
            try {
                parcel.writeBundle(config.toBundle())
                parcel.setDataPosition(0)
                SavedSocketConfig.fromBundle(parcel.readBundle(javaClass.classLoader))!!
            } finally {
                parcel.recycle()
            }
        assertEquals(config.uri, restored.uri)
        assertEquals(config.transports, restored.transports)
        assertEquals(config.query, restored.query)
        assertEquals(config.extraHeaders, restored.extraHeaders)
        assertEquals(config.namespaces, restored.namespaces)
        assertEquals(null, SavedSocketConfig.fromBundle(Bundle()))
    }
}
