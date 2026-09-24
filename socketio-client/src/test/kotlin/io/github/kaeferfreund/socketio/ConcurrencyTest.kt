package io.github.kaeferfreund.socketio

import io.github.kaeferfreund.socketio.engineio.ProtocolExecutor
import io.github.kaeferfreund.socketio.testing.FakeSocketIOServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The concurrency contract: any thread may call the API; all protocol state
 * lives on one serial executor. Lincheck checks the confinement mechanism and
 * the listener registry; a stress test drives the whole client from many
 * threads against the in-memory server on real dispatchers.
 */
class ConcurrencyTest {
    /** State touched only through the executor, deliberately without any synchronization. */
    class ConfinedCounter {
        private val executor = ProtocolExecutor(Dispatchers.Default)
        private var value = 0

        @Operation
        fun increment(): Int {
            val latch = CountDownLatch(1)
            var result = 0
            executor.execute {
                value += 1
                result = value
                latch.countDown()
            }
            latch.await()
            return result
        }

        @Operation
        fun get(): Int {
            val latch = CountDownLatch(1)
            var result = 0
            executor.execute {
                result = value
                latch.countDown()
            }
            latch.await()
            return result
        }
    }

    @Test
    fun theProtocolExecutorLinearizesUnsynchronizedState() {
        StressOptions().iterations(30).invocationsPerIteration(200).threads(3).actorsPerThread(3).check(ConfinedCounter::class)
    }

    /** The socket's named-listener registry as a concurrent multiset. */
    class ListenerRegistry {
        private val manager = SocketManager("http://unused.test", SocketManagerOptions { autoConnect = false })
        private val socket = manager.socket()

        // Distinct instances: a non-capturing lambda would be one shared singleton.
        private val listeners = List(2) { index -> EventListener { event -> event.hashCode() + index } }

        @Operation
        fun add(index: Int) {
            socket.on("e", listeners[Math.floorMod(index, 2)])
        }

        @Operation
        fun remove(index: Int) {
            socket.off("e", listeners[Math.floorMod(index, 2)])
        }

        @Operation
        fun count(index: Int): Int = socket.listeners("e").count { it === listeners[Math.floorMod(index, 2)] }
    }

    @Test
    fun theListenerRegistryIsLinearizable() {
        ModelCheckingOptions().iterations(20).invocationsPerIteration(500).threads(2).actorsPerThread(2).check(ListenerRegistry::class)
    }

    @Test
    fun manyThreadsEmittingWithAcknowledgementsLoseNothingAndDuplicateNothing() {
        // The in-memory server is single-threaded by contract.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val server = FakeSocketIOServer(scope)
        server.namespace("/").onConnection { client -> client.on("echo") { args, ack -> ack?.invoke(args) } }
        val manager = SocketManager("http://fake.test", SocketManagerOptions { clients = server.clients })
        try {
            val socket = manager.socket()
            val threads = 8
            val perThread = 250
            val acknowledged = ConcurrentHashMap<String, Int>()
            val done = CountDownLatch(threads * perThread)
            val start = CyclicBarrier(threads)
            val disconnects = AtomicInteger()
            socket.onDisconnect { _, _ -> disconnects.incrementAndGet() }
            val workers =
                List(threads) { t ->
                    thread(name = "emitter-$t") {
                        start.await()
                        repeat(perThread) { i ->
                            val key = "$t-$i"
                            socket.emit("echo", key) { result ->
                                acknowledged.merge(result.getOrThrow()[0].string!!, 1, Int::plus)
                                done.countDown()
                            }
                        }
                    }
                }
            workers.forEach { it.join() }
            assertTrue(done.await(60, TimeUnit.SECONDS), "only ${threads * perThread - done.count} acknowledgements arrived")
            assertEquals(threads * perThread, acknowledged.size)
            assertTrue(acknowledged.values.all { it == 1 }, "duplicate acknowledgements")
            assertEquals(0, disconnects.get())
            // Per-thread order is kept: the server received each thread's emits in order.
            val received = server.namespace("/").clients.single().events.filter { it.first == "echo" }.map { it.second[0].string!! }
            for (t in 0 until threads) {
                val sequence = received.filter { it.startsWith("$t-") }.map { it.substringAfter('-').toInt() }
                assertEquals((0 until perThread).toList(), sequence)
            }
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    @Test
    fun concurrentConnectAndDisconnectEndInAConsistentState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val server = FakeSocketIOServer(scope)
        val manager = SocketManager("http://fake.test", SocketManagerOptions { clients = server.clients })
        try {
            val socket = manager.socket()
            val connects = AtomicInteger()
            val disconnects = AtomicInteger()
            socket.onConnect { connects.incrementAndGet() }
            socket.onDisconnect { _, _ -> disconnects.incrementAndGet() }
            val start = CyclicBarrier(4)
            val workers =
                List(4) { t ->
                    thread {
                        start.await()
                        repeat(200) { i -> if ((i + t) % 2 == 0) socket.connect() else socket.disconnect() }
                    }
                }
            workers.forEach { it.join() }
            socket.disconnect()
            // Let the executor drain.
            val drained = CountDownLatch(1)
            manager.executor.post { drained.countDown() }
            assertTrue(drained.await(10, TimeUnit.SECONDS))
            Thread.sleep(200)
            val finalDrain = CountDownLatch(1)
            manager.executor.post { finalDrain.countDown() }
            assertTrue(finalDrain.await(10, TimeUnit.SECONDS))
            assertTrue(!socket.connected)
            // Every disconnect event pairs with an earlier connect event.
            assertEquals(connects.get(), disconnects.get())
        } finally {
            manager.close()
            scope.cancel()
        }
    }
}
