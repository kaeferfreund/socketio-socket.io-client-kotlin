package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** What counts as running on the executor, which decides between running inline and queueing. */
class ProtocolExecutorTest {
    @Test
    fun aChildCoroutineOnAnotherDispatcherIsNotOnTheExecutor() {
        val executor = ProtocolExecutor(Dispatchers.Default)
        val other = Executors.newSingleThreadExecutor()
        try {
            val seen = CompletableFuture<List<Boolean>>()
            executor.execute {
                val onExecutor = executor.isCurrent
                executor.scope.launch(other.asCoroutineDispatcher()) {
                    val inChild = executor.isCurrent
                    var ranOn: Thread? = null
                    executor.execute { ranOn = Thread.currentThread() }
                    seen.complete(listOf(onExecutor, inChild, ranOn === Thread.currentThread()))
                }
            }
            assertEquals(listOf(true, false, false), seen.get(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdown()
            other.shutdown()
        }
    }

    @Test
    fun nextTickRunsBeforeWorkQueuedEarlierLikeAMicrotask() =
        runTest {
            val executor = ProtocolExecutor(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
            val order = ArrayList<String>()
            executor.post {
                order += "a"
                executor.nextTick { order += "a: tick 1" }
                executor.nextTick { order += "a: tick 2" }
            }
            executor.post { order += "b" }
            executor.schedule(1.seconds) {
                order += "timer"
                executor.nextTick { order += "timer: tick" }
            }
            executor.post { order += "c" }
            advanceUntilIdle()
            assertEquals(listOf("a", "a: tick 1", "a: tick 2", "b", "c", "timer", "timer: tick"), order)
            executor.shutdown()
        }

    @Test
    fun workRejectedByAStoppedExecutorIsReported() {
        val executor = ProtocolExecutor(Dispatchers.Default)
        executor.shutdown()
        assertTrue(executor.isShutdown)
        val rejected = CompletableFuture<Boolean>()
        executor.execute({ rejected.complete(false) }, onRejected = { rejected.complete(true) })
        assertTrue(rejected.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun withContextToAnotherDispatcherLeavesTheExecutorAndComesBack() {
        val executor = ProtocolExecutor(Dispatchers.Default)
        val other = Executors.newSingleThreadExecutor()
        try {
            val seen = CompletableFuture<List<Boolean>>()
            executor.scope.launch {
                val before = executor.isCurrent
                val inside = withContext(other.asCoroutineDispatcher()) { executor.isCurrent }
                seen.complete(listOf(before, inside, executor.isCurrent))
            }
            assertEquals(listOf(true, false, true), seen.get(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdown()
            other.shutdown()
        }
    }
}
