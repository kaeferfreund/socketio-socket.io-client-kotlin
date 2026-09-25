package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
