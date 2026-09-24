package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Listener registration, ordering, the queue-turn guarantees of JavaScript and the Flow API. */
class SocketListenerTest {
    // JS-093: removing a listener takes effect for the immediately following emit on the same turn.
    @Test
    fun removingAnOutgoingListenerIsEffectiveForTheNextEmit() =
        runClientTest {
            val h = clientHarness()
            val socket = h.manager().socket()
            h.settle()
            var called = 0
            val listener = AnyListener { _, _ -> called++ }
            h.onExecutor(socket.manager) {
                socket.onAnyOutgoing(listener)
                socket.offAnyOutgoing(listener)
                socket.emit("my-event", "123")
            }
            assertEquals(0, called)
            assertTrue(socket.listenersAnyOutgoing().isEmpty())
        }

    // JS-091 (unit counterpart): an outgoing listener added inside connect sees binary at once.
    @Test
    fun anOutgoingListenerAddedInConnectSeesABinaryEmitWithoutAQueueTurn() =
        runClientTest {
            val h = clientHarness()
            val seen = ArrayList<Pair<String, List<SocketIOValue>>>()
            h.manager().socket {
                onConnect {
                    onAnyOutgoing { name, args -> seen += name to args }
                    emit("my-event", byteArrayOf(1, 2, 3))
                    // Synchronously visible on the protocol executor, before any other turn.
                    check(seen.size == 1)
                }
            }
            h.settle()
            assertEquals(listOf("my-event" to listOf(SocketIOValue.Binary(byteArrayOf(1, 2, 3)))), seen)
        }

    @Test
    fun onceListenersFireOnceAndOffRemovesByInstanceOrName() =
        runClientTest {
            val h = clientHarness { namespace("/").onConnection { c -> repeat(2) { c.emit("tick", it) } } }
            val ticks = ArrayList<Long>()
            val onceTicks = ArrayList<Long>()
            val socket =
                h.manager().socket {
                    once("tick") { onceTicks += it[0]!!.long!! }
                    on("tick") { ticks += it[0]!!.long!! }
                }
            h.settle()
            assertEquals(listOf(0L, 1L), ticks)
            assertEquals(listOf(0L), onceTicks)
            assertTrue(socket.hasListeners("tick"))
            socket.off("tick")
            assertTrue(!socket.hasListeners("tick"))
        }

    @Test
    fun reservedNamesReceiveLifecycleEventsLikeJavaScript() =
        runClientTest {
            val h = clientHarness()
            val seen = ArrayList<String>()
            val socket =
                h.manager().socket {
                    on("connect") { seen += "connect" }
                    on("disconnect") { seen += "disconnect:" + it[0]!!.string }
                }
            h.settle()
            socket.disconnect()
            h.settle()
            assertEquals(listOf("connect", "disconnect:io client disconnect"), seen)
        }

    @Test
    fun theFlowApiDeliversStateAndEvents() =
        runClientTest {
            val h = clientHarness { namespace("/").onConnection { c -> c.on("ping-me") { _, _ -> c.emit("pong", "x") } } }
            val socket = h.manager { autoConnect = false }.socket()
            val received = ArrayList<IncomingEvent>()
            val job = launch { socket.flow("pong").take(1).toList(received) }
            val lifecycle = ArrayList<SocketEvent>()
            val lifecycleJob = launch { socket.events.collect { lifecycle += it } }
            runCurrent()
            assertTrue(socket.state.value is ConnectionState.Disconnected)
            socket.connect()
            h.settle()
            val connected = socket.state.first { it is ConnectionState.Connected } as ConnectionState.Connected
            assertEquals(socket.id, connected.id)
            socket.emit("ping-me")
            h.settle()
            job.join()
            assertEquals("x", received.single()[0]!!.string)
            assertTrue(lifecycle.first() is SocketEvent.Connected)
            lifecycleJob.cancel()
        }

    @Test
    fun outgoingInterceptorsSeeBufferedSentAndDroppedEmits() =
        runClientTest {
            val h = clientHarness()
            val log = ArrayList<String>()
            val interceptor =
                object : OutgoingInterceptor {
                    override fun onBuffered(event: OutgoingEvent) {
                        log += "buffered:${event.name}"
                    }

                    override fun onSent(event: OutgoingEvent) {
                        log += "sent:${event.name}"
                    }

                    override fun onDropped(
                        event: OutgoingEvent,
                        reason: Throwable?,
                    ) {
                        log += "dropped:${event.name}"
                    }
                }
            val socket = h.manager { autoConnect = false }.socket(options = SocketOptions { outgoingInterceptor = interceptor })
            socket.emit("a")
            socket.volatile.emit("b")
            h.settle()
            socket.connect()
            h.settle()
            socket.emit("c")
            h.settle()
            assertEquals(listOf("buffered:a", "dropped:b", "sent:a", "sent:c"), log)
        }
}
