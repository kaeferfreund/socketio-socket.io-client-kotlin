package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.SocketOptions
import io.github.kaeferfreund.socketio.Transport
import io.github.kaeferfreund.socketio.engineio.EnginePacketObserver
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Ports of `packages/socket.io-client/test/retry.ts`. Socket.IO packets are read from the engine packet observer (`packetCreate`). */
class RetryE2ETest {
    private fun observer(
        sent: MutableList<String>,
        received: MutableList<String>? = null,
    ) = EnginePacketObserver { outgoing, packet ->
        val text = packet.text ?: return@EnginePacketObserver
        if (outgoing) sent += text else received?.add(text)
    }

    // JS-055. The acknowledgement timer starts at emit, so the original emits before the
    // connection exists. Here the socket is connected and upgraded first: the 50 ms budget
    // then covers the roundtrips only, not the handshake on a loaded machine.
    @Test
    fun preservesTheOrderOfThePackets() =
        e2e {
            val sent = CopyOnWriteArrayList<String>()
            val socket =
                io(
                    socketOptions = SocketOptions {
                        retries = 1
                        ackTimeout = 50.milliseconds
                    },
                ) { packetObserver = observer(sent) }
            socket.awaitConnect()
            socket.manager.transportName.first { it == Transport.WEBSOCKET }
            val queueLengths = CopyOnWriteArrayList<Int>()
            val done = CompletableDeferred<Pair<Throwable?, SocketIOValue?>>()
            socket.manager.executor.execute {
                socket.emit("echo", 1) { queueLengths += socket.pendingEmits.value }
                queueLengths += socket.pendingEmits.value
                socket.emit("echo", 2) { queueLengths += socket.pendingEmits.value }
                queueLengths += socket.pendingEmits.value
                socket.emit("echo", 3) { result ->
                    queueLengths += socket.pendingEmits.value
                    done.complete(result.exceptionOrNull() to result.getOrNull()?.firstOrNull())
                }
                queueLengths += socket.pendingEmits.value
            }
            val (error, value) = withTimeout(10.seconds) { done.await() }
            assertNull(error)
            assertEquals(3L, value!!.long)
            socket.disconnect()
            delay(50.milliseconds)
            // Queue length right after each emit (1, 2, 3), then inside each acknowledgement (2, 1, 0).
            assertEquals(listOf(1, 2, 3, 2, 1, 0), queueLengths)
            assertEquals(listOf("0", "20[\"echo\",1]", "21[\"echo\",2]", "22[\"echo\",3]", "1"), sent.filter { it != "3" })
        }

    // JS-056
    @Test
    fun failsWhenTheServerDoesNotAcknowledgeThePacket() =
        e2e {
            val sent = CopyOnWriteArrayList<String>()
            val count = AtomicInteger()
            val done = CompletableDeferred<Int>()
            val socket =
                io(
                    socketOptions = SocketOptions {
                        retries = 3
                        ackTimeout = 50.milliseconds
                    },
                    setup = {
                        on("ack") { count.incrementAndGet() }
                        emit("ack") { done.complete(count.get()) }
                    },
                ) { packetObserver = observer(sent) }
            assertEquals(4, withTimeout(10.seconds) { done.await() })
            socket.disconnect()
            delay(50.milliseconds)
            assertEquals(listOf("0", "20[\"ack\"]", "21[\"ack\"]", "22[\"ack\"]", "23[\"ack\"]", "1"), sent.filter { it != "3" })
        }

    // JS-057: JavaScript uses a 10 ms acknowledgement timeout, which a cold JVM roundtrip can
    // exceed. 20 ms keeps the test's power: had the queue drained while disconnected, all four
    // tries (80 ms) would have failed before the 100 ms wait ends. The virtual-time unit test
    // `SocketRetryTest.doesNotDrainTheQueueWhileDisconnected` asserts the exact 10 ms case.
    @Test
    fun doesNotDrainTheQueueWhileDisconnected() =
        e2e {
            val socket =
                io(
                    socketOptions = SocketOptions {
                        retries = 3
                        ackTimeout = 20.milliseconds
                    },
                ) { autoConnect = false }
            val result = CompletableDeferred<Throwable?>()
            socket.emit("echo", 1) { result.complete(it.exceptionOrNull()) }
            delay(100.milliseconds)
            socket.connect()
            assertNull(withTimeout(10.seconds) { result.await() })
            socket.disconnect()
        }

    // JS-058
    @Test
    fun doesNotEmitAPacketTwiceInTheConnectHandler() =
        e2e {
            val sent = CopyOnWriteArrayList<String>()
            val received = CopyOnWriteArrayList<Pair<String, String?>>()
            val socket =
                io(socketOptions = SocketOptions { retries = 3 }, setup = { onConnect { emit("echo", null) } }) {
                    packetObserver =
                        EnginePacketObserver { outgoing, packet ->
                            if (outgoing) sent += packet.text.orEmpty() else received += packet.type.wireName to packet.text
                        }
                    // Polling only, so no upgrade noop/probe packets appear in the observation.
                    transports = listOf(io.github.kaeferfreund.socketio.Transport.POLLING)
                }
            delay(300.milliseconds)
            assertEquals(listOf("0", "20[\"echo\",null]"), sent)
            // 1: engine.io OPEN packet, 2: socket.io CONNECT packet, 3: ack packet
            assertEquals(3, received.size, "$received")
            assertEquals("open", received[0].first)
            assertEquals("30[null]", received[2].second)
            socket.disconnect()
        }
}
