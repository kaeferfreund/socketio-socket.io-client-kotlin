package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.AuthProvider
import io.github.kaeferfreund.socketio.DisconnectReason
import io.github.kaeferfreund.socketio.ManagerEvent
import io.github.kaeferfreund.socketio.SocketBufferLimitException
import io.github.kaeferfreund.socketio.SocketBufferLimits
import io.github.kaeferfreund.socketio.SocketConnectException
import io.github.kaeferfreund.socketio.SocketOptions
import io.github.kaeferfreund.socketio.Transport
import io.github.kaeferfreund.socketio.engineio.EnginePacketObserver
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Mobile failure modes beyond the upstream suite (plan section 6.3, KT
 * contracts), against real servers: token renewal, network loss in the middle
 * of an upgrade, a polling POST and an acknowledgement, a sleeping device and
 * bounded buffers.
 */
class ResilienceE2ETest {
    // KT-AUTH-RENEWAL
    @Test
    fun aRevokedTokenIsReplacedByTheProviderOnTheNextAttempt() =
        e2e("auth-server.mjs") {
            val token = AtomicReference("token-1")
            val errors = CopyOnWriteArrayList<Throwable>()
            val socket =
                manager { reconnectionDelay = 50.milliseconds }.socket(
                    "/",
                    SocketOptions { authProvider = AuthProvider { mapOf("token" to token.get()) } },
                ) { onConnectError { error -> errors += error } }
            socket.awaitConnect()
            assertEquals("token-1", socket.emitWithAck("whoami")[0].string)
            server.admin("/admin/revoke?token=token-1")
            val reconnected = CompletableDeferred<Unit>()
            socket.onConnect { reconnected.complete(Unit) }
            // The refresh happens while the connection is gone, as an app would do on 401.
            token.set("token-2")
            server.admin("/admin/kill?sid=${socket.id}")
            withTimeout(10.seconds) { reconnected.await() }
            assertEquals("token-2", socket.emitWithAck("whoami")[0].string)
            assertTrue(errors.isEmpty(), "$errors")
            socket.disconnect()
        }

    // KT-AUTH-RENEWAL: a rejected token surfaces as connect_error with the server's data;
    // the socket stays inactive until the app connects again with a new token.
    @Test
    fun aRejectedTokenFailsWithServerDataAndConnectsAfterRenewal() =
        e2e("auth-server.mjs") {
            val token = AtomicReference("revoked")
            server.admin("/admin/revoke?token=revoked")
            val error = CompletableDeferred<Throwable>()
            val socket =
                manager().socket("/", SocketOptions { authProvider = AuthProvider { mapOf("token" to token.get()) } }) {
                    onConnectError { error.complete(it) }
                }
            val refused = withTimeout(10.seconds) { error.await() } as SocketConnectException
            assertEquals("token rejected", refused.message)
            assertTrue(refused.isServerRefusal)
            assertEquals("revoked", refused.data!!.obj!!["token"]!!.string)
            assertTrue(!socket.active)
            token.set("fresh")
            socket.connect()
            socket.awaitConnect()
            assertEquals("fresh", socket.emitWithAck("whoami")[0].string)
            socket.disconnect()
        }

    // KT-NET-UPGRADE: the network drops while the WebSocket probe is running.
    @Test
    fun losingTheNetworkDuringTheUpgradeReconnectsCleanly() =
        e2e {
            val lost = CompletableDeferred<Unit>()
            lateinit var manager: io.github.kaeferfreund.socketio.SocketManager
            manager =
                manager(
                    setup = {
                        on<ManagerEvent.Open> {
                            // Right after open the probe is in flight; drop the connection then.
                            if (!lost.isCompleted) {
                                lost.complete(Unit)
                                onNetworkLost()
                            }
                        }
                    },
                ) { reconnectionDelay = 50.milliseconds }
            val reasons = CopyOnWriteArrayList<DisconnectReason>()
            val socket = manager.socket("/") { onDisconnect { reason, _ -> reasons += reason } }
            withTimeout(10.seconds) { lost.await() }
            socket.awaitConnect()
            manager.transportName.first { it == Transport.WEBSOCKET }
            assertEquals("ok", socket.emitWithAck("echo", "ok")[0].string)
            assertTrue(reasons.all { it == DisconnectReason.TRANSPORT_CLOSE }, "$reasons")
            socket.disconnect()
        }

    // KT-NET-POST: the network drops while a polling POST carries an acknowledged emit;
    // the retry queue resends it after reconnecting, exactly once acknowledged.
    @Test
    fun anEmitInterruptedDuringAPollingPostIsRetriedAfterReconnecting() =
        e2e {
            val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var manager: io.github.kaeferfreund.socketio.SocketManager
            manager =
                manager {
                    transports = listOf(Transport.POLLING)
                    reconnectionDelay = 50.milliseconds
                    packetObserver =
                        EnginePacketObserver { outgoing, packet ->
                            if (outgoing && packet.type == EngineIOPacketType.MESSAGE && packet.text?.contains("interrupted") == true &&
                                interrupted.compareAndSet(false, true)
                            ) {
                                manager.onNetworkLost()
                            }
                        }
                }
            val socket = manager.socket(
                "/",
                SocketOptions {
                    retries = 3
                    ackTimeout = 2.seconds
                },
            )
            socket.awaitConnect()
            val reply = socket.emitWithAck("echo", "interrupted")
            assertEquals("interrupted", reply[0].string)
            assertTrue(interrupted.get())
            socket.disconnect()
        }

    // KT-NET-ACK: the connection drops while the server is still working on an acknowledgement.
    @Test
    fun anAcknowledgementLostWithTheConnectionFailsOrIsRetried() =
        e2e("auth-server.mjs", mapOf("SLOW_MS" to "500")) {
            val socket =
                manager { reconnectionDelay = 50.milliseconds }.socket(
                    "/",
                    SocketOptions { authProvider = AuthProvider { mapOf("token" to "ok") } },
                )
            socket.awaitConnect()
            // Without retries a plain timed acknowledgement fails with the disconnection.
            val result =
                coroutineScope {
                    val pending = async { runCatching { socket.timeout(5.seconds).emitWithAck("slow-echo", 1) } }
                    delay(100.milliseconds)
                    socket.manager.onNetworkLost()
                    pending.await()
                }
            assertTrue(result.exceptionOrNull() is io.github.kaeferfreund.socketio.SocketDisconnectedException, "$result")
            socket.awaitConnect()
            // With retries the same emit survives the drop.
            val retrying =
                manager { reconnectionDelay = 50.milliseconds }.socket(
                    "/",
                    SocketOptions {
                        authProvider = AuthProvider { mapOf("token" to "ok") }
                        retries = 3
                        ackTimeout = 2.seconds
                    },
                )
            retrying.awaitConnect()
            val survived =
                coroutineScope {
                    val call = async { retrying.emitWithAck("slow-echo", 2) }
                    delay(100.milliseconds)
                    retrying.manager.onNetworkLost()
                    call.await()
                }
            assertEquals(2L, survived[0].long)
            socket.disconnect()
            retrying.disconnect()
        }

    // KT-DOZE: the device slept past the heartbeat deadline; on wake-up exactly one
    // "ping timeout" disconnect and one reconnect follow, and nothing is duplicated.
    @Test
    fun wakingUpAfterTheHeartbeatDeadlineGivesOneDisconnectAndOneReconnect() =
        e2e {
            val clock = kotlin.time.TestTimeSource()
            val reasons = CopyOnWriteArrayList<DisconnectReason>()
            val reconnects = CopyOnWriteArrayList<Int>()
            val socket =
                io(setup = {
                    onDisconnect { reason, _ -> reasons += reason }
                    manager.on<ManagerEvent.Reconnect> { reconnects += it.attempt }
                }) {
                    reconnectionDelay = 50.milliseconds
                    timeSource = clock
                }
            socket.awaitConnect()
            val woke = CompletableDeferred<String?>()
            socket.manager.executor.execute {
                clock += 1.seconds * 3600
                // Several emits right after waking: each checks the heartbeat, only one close follows.
                repeat(3) { index -> socket.emit("echo", "after-sleep-$index") { if (index == 2) woke.complete(it.getOrNull()?.get(0)?.string) } }
            }
            assertEquals("after-sleep-2", withTimeout(10.seconds) { woke.await() })
            eventually { reconnects.isNotEmpty() }
            // Negative check: no second disconnect or reconnect follows.
            delay(300.milliseconds)
            assertEquals(listOf(DisconnectReason.PING_TIMEOUT), reasons)
            assertEquals(1, reconnects.size)
            socket.disconnect()
        }

    // KT-BUFFER: a bounded send buffer fails the new emit instead of growing without limit.
    @Test
    fun aFullSendBufferRejectsTheNewEmitWithoutEvictingAcceptedOnes() =
        e2e {
            val socket = manager {
                autoConnect = false
                bufferLimits = SocketBufferLimits(maxSendBufferPackets = 2)
            }.socket("/")
            val results = CopyOnWriteArrayList<Result<*>>()
            repeat(3) { index -> socket.emit("echo", index) { results += it } }
            // The third emit is rejected after the first two were buffered, in executor order.
            eventually { results.isNotEmpty() }
            assertEquals(1, results.size)
            assertTrue(results.single().exceptionOrNull() is SocketBufferLimitException)
            assertEquals(2, socket.pendingEmits.value)
            socket.connect()
            socket.awaitConnect()
            eventually { results.size >= 3 }
            assertEquals(3, results.size)
            assertEquals(2, results.count { it.isSuccess })
            socket.disconnect()
        }
}
