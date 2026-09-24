package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** The polling → WebSocket upgrade: `SocketWithUpgrade` in `engine.io-client`. */
class EngineUpgradeTest {
    @BeforeEach
    fun resetPriorWebsocketSuccess() {
        EngineSocket.priorWebsocketSuccess.set(false)
    }

    @Test
    fun upgradesFromPollingToWebSocket() =
        runTest {
            val h = engineHarness()
            val engine = h.engine()
            h.settle()
            assertEquals("websocket", engine.transport!!.name)
            assertEquals(listOf("websocket"), h.all<EngineEvent.Upgrading>().map { it.transport.name })
            assertEquals(listOf("websocket"), h.all<EngineEvent.Upgrade>().map { it.transport.name })
            val session = h.server.sessions.values.single()
            assertEquals("websocket", session.transport)
            assertTrue(session.received.any { it.type == EngineIOPacketType.UPGRADE })
            h.executor.execute { engine.send("after upgrade") }
            h.settle()
            assertEquals(listOf("hi", "after upgrade"), h.messages)
            // The upgrade handshake used the same session id.
            assertEquals("sid0", h.server.requests.last { it.method == "UPGRADE" }.query["sid"])
            h.close()
        }

    @Test
    fun packetsSentWhileUpgradingAreBufferedAndFlushedOverTheNewTransport() =
        runTest {
            val h = engineHarness()
            val engine = h.engine()
            h.executor.execute {
                engine.events.on(EngineEvent.Upgrading::class.java) { engine.send("during upgrade") }
            }
            h.settle()
            assertEquals(listOf("hi", "during upgrade"), h.messages)
            val ws = h.server.sessions.values.single()
            // Sent over the WebSocket after the upgrade packet, never over polling.
            assertTrue(h.server.requests.none { it.method == "POST" && it.body!!.contains("during upgrade") })
            assertEquals(EngineIOPacketType.UPGRADE, ws.received.first { it.type != EngineIOPacketType.PONG }.type)
            h.close()
        }

    @Test
    fun theUpgradeWaitsForAPendingPost() =
        runTest {
            val h = engineHarness()
            h.server.latency = 10.milliseconds
            val engine = h.engine()
            // Send just before the probe answers, so a POST is in flight during the pause.
            h.executor.execute {
                engine.events.on(EngineEvent.Open::class.java) { engine.send("racing post") }
            }
            advanceTimeBy(500.milliseconds)
            assertEquals("websocket", engine.transport!!.name)
            assertEquals(listOf("hi", "racing post"), h.messages)
            val session = h.server.sessions.values.single()
            val order = session.received.map { it.type }
            assertTrue(order.indexOf(EngineIOPacketType.MESSAGE) < order.indexOf(EngineIOPacketType.UPGRADE), "$order")
            h.close()
        }

    @Test
    fun aFailedProbeKeepsPollingAndReportsUpgradeError() =
        runTest {
            val h = engineHarness()
            h.server.allowRequest = { if (it.method == "UPGRADE") 403 else null }
            val engine = h.engine()
            h.settle()
            assertEquals("polling", engine.transport!!.name)
            assertEquals(EngineState.OPEN, engine.readyState)
            val error = h.all<EngineEvent.UpgradeError>().single()
            assertEquals("websocket", error.transportName)
            assertTrue(error.error.message!!.startsWith("probe error"))
            h.close()
        }

    @Test
    fun upgradeDisabledStaysOnPolling() =
        runTest {
            val h = engineHarness()
            val engine = h.engine(EngineOptions(upgrade = false))
            h.settle()
            assertEquals("polling", engine.transport!!.name)
            assertTrue(h.server.requests.none { it.method == "UPGRADE" })
            h.close()
        }

    // JS-153
    @Test
    fun defersCloseWhenUpgrading() =
        runTest {
            val h = engineHarness()
            val engine = h.engine()
            var upgradedBeforeClose: Boolean? = null
            h.executor.execute {
                var upgraded = false
                engine.events.on(EngineEvent.Upgrade::class.java) { upgraded = true }
                engine.events.on(EngineEvent.Upgrading::class.java) {
                    engine.events.on(EngineEvent.Close::class.java) { upgradedBeforeClose = upgraded }
                    engine.close()
                }
            }
            h.settle()
            assertEquals(true, upgradedBeforeClose)
            h.close()
        }

    // JS-154: the pending long poll fails while the close is deferred.
    @Test
    fun closesOnUpgradeErrorIfClosingIsDeferred() =
        runTest {
            val h = engineHarness()
            // Keep the long poll pending so it can fail while the close waits for the upgrade.
            h.server.releasePollOnProbe = false
            val engine = h.engine()
            var upgradeErrorBeforeClose: Boolean? = null
            h.executor.execute {
                var upgradeError = false
                engine.events.on(EngineEvent.UpgradeError::class.java) { upgradeError = true }
                engine.events.on(EngineEvent.Upgrading::class.java) {
                    engine.events.on(EngineEvent.Close::class.java) { upgradeErrorBeforeClose = upgradeError }
                    engine.close()
                    h.server.sessions.values.single().failPendingPoll(500)
                }
            }
            advanceTimeBy(1000.milliseconds)
            assertEquals(true, upgradeErrorBeforeClose)
            assertEquals(EngineState.CLOSED, engine.readyState)
            h.close()
        }

    // JS-155
    @Test
    fun doesNotSendPacketsIfClosingIsDeferred() =
        runTest {
            val h = engineHarness()
            val engine = h.engine()
            var created = 0
            h.executor.execute {
                engine.events.on(EngineEvent.Upgrading::class.java) {
                    engine.events.on(EngineEvent.PacketCreated::class.java) { created++ }
                    engine.close()
                    engine.send("hi")
                }
            }
            advanceTimeBy(200.milliseconds)
            assertEquals(0, created)
            h.close()
        }

    // JS-156
    @Test
    fun sendsAllBufferedPacketsIfClosingIsDeferred() =
        runTest {
            val h = engineHarness()
            val engine = h.engine()
            var bufferAtClose = -1
            h.executor.execute {
                engine.events.on(EngineEvent.Upgrading::class.java) {
                    engine.send("hi")
                    engine.close()
                }
                engine.events.on(EngineEvent.Close::class.java) { bufferAtClose = engine.writeBuffer.size }
            }
            h.settle()
            assertEquals(0, bufferAtClose)
            assertTrue(h.server.sessions.values.single().messages.contains(EngineIOData.Text("hi")))
            h.close()
        }

    // JS-199
    @Test
    fun remembersAWebSocketUpgrade() =
        runTest {
            val h = engineHarness()
            val first = h.engine(open = false)
            var firstTransport = ""
            h.executor.execute {
                first.open()
                firstTransport = first.transport!!.name
            }
            h.settle()
            assertEquals("polling", firstTransport)
            assertEquals("websocket", first.transport!!.name)
            h.executor.execute { first.close() }
            h.settle()
            val second = h.engine(EngineOptions(rememberUpgrade = true), open = false)
            var secondTransport = ""
            h.executor.execute {
                second.open()
                secondTransport = second.transport!!.name
            }
            h.settle()
            assertEquals("websocket", secondTransport)
            assertEquals(EngineState.OPEN, second.readyState)
            h.close()
        }

    // JS-200
    @Test
    fun doesNotRememberAWebSocketUpgradeByDefault() =
        runTest {
            val h = engineHarness()
            val first = h.engine()
            h.settle()
            assertEquals("websocket", first.transport!!.name)
            val second = h.engine(EngineOptions(rememberUpgrade = false), open = false)
            var secondTransport = ""
            h.executor.execute {
                second.open()
                secondTransport = second.transport!!.name
            }
            assertEquals("polling", secondTransport.ifEmpty { h.settle().let { secondTransport } })
            h.close()
        }
}
