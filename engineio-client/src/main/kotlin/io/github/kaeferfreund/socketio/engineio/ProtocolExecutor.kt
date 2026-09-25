package io.github.kaeferfreund.socketio.engineio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The single serial context that owns all protocol state of one manager: the
 * engine, its transports, the manager and every socket. It is the Kotlin
 * counterpart of the JavaScript event loop and of the Swift fork's
 * `handleQueue`.
 *
 * Work submitted with [execute] runs inline when the caller is already on this
 * executor (so a listener that emits keeps the JavaScript ordering) and is
 * queued in FIFO order otherwise. Timers run on the same executor.
 *
 * @param dispatcher the dispatcher to run on; it is limited to parallelism 1.
 *   Tests pass a `StandardTestDispatcher` to run on virtual time.
 * @param timeSource monotonic time for deadlines. Android uses
 *   `SystemClock.elapsedRealtime()`, which keeps counting in deep sleep.
 */
public class ProtocolExecutor(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial: CoroutineDispatcher = dispatcher.limitedParallelism(1)

    private val marker = Marker(this)

    /** Scope of every protocol coroutine; cancelled by [shutdown]. */
    @InternalSocketIOApi
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + serial + marker)

    /** `true` when the calling thread is currently running work of this executor. */
    public val isCurrent: Boolean get() = CURRENT.get() === this

    /** `true` after [shutdown]: queued work is dropped. */
    public val isShutdown: Boolean get() = !scope.isActive

    /** Microtasks of the running task (JavaScript's `nextTick` queue). Executor only. */
    private val microtasks = ArrayDeque<() -> Unit>()
    private var inTask = false

    /** Runs [block] now if already on this executor, otherwise queues it. */
    public fun execute(block: () -> Unit) {
        if (isCurrent) block() else post(block)
    }

    /**
     * Like [execute], but calls [onRejected] (on any thread) instead of [block]
     * when the executor is shut down before [block] could run, so a caller
     * waiting for the result is never left hanging.
     */
    @InternalSocketIOApi
    public fun execute(
        block: () -> Unit,
        onRejected: () -> Unit,
    ) {
        if (isCurrent) {
            block()
            return
        }
        val started = AtomicBoolean(false)
        scope
            .launch {
                started.set(true)
                runTask(block)
            }.invokeOnCompletion { if (!started.get()) onRejected() }
    }

    /** Always queues [block] behind the work already queued, even from this executor (JavaScript `setTimeout(…, 0)`). */
    public fun post(block: () -> Unit) {
        scope.launch { runTask(block) }
    }

    /**
     * Runs [block] right after the task that is running now, before any other
     * queued work: JavaScript's `nextTick`, a microtask. A packet decoded from a
     * transport event is therefore handled before a close event that was already
     * queued behind it, as in JavaScript. Off the executor it is the same as [post].
     */
    @InternalSocketIOApi
    public fun nextTick(block: () -> Unit) {
        if (!isCurrent) {
            post(block)
            return
        }
        microtasks.addLast(block)
        if (!inTask) post { }
    }

    /** Runs one task, then its microtasks. */
    private fun runTask(block: () -> Unit) {
        inTask = true
        try {
            block()
        } finally {
            try {
                while (true) (microtasks.removeFirstOrNull() ?: break)()
            } finally {
                inTask = false
            }
        }
    }

    /**
     * Runs [block] on this executor after [delay] (JavaScript `setTimeout`).
     * Cancel the returned handle to clear the timer.
     */
    public fun schedule(
        delay: Duration,
        block: () -> Unit,
    ): Cancellable {
        val job: Job =
            scope.launch {
                delay(delay)
                runTask(block)
            }
        return Cancellable { job.cancel() }
    }

    /** Stops every pending task and timer. The executor cannot be used afterwards. */
    public fun shutdown() {
        scope.cancel()
    }

    private class Marker(
        val owner: ProtocolExecutor,
    ) : AbstractCoroutineContextElement(Key),
        ThreadContextElement<ProtocolExecutor?> {
        companion object Key : CoroutineContext.Key<Marker>

        override fun updateThreadContext(context: CoroutineContext): ProtocolExecutor? {
            val previous = CURRENT.get()
            // A child launched on another dispatcher (a listener callback, a provider's
            // withContext) inherits this element but does not run on the executor.
            CURRENT.set(if (context[ContinuationInterceptor] === owner.serial) owner else null)
            return previous
        }

        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: ProtocolExecutor?,
        ) {
            CURRENT.set(oldState)
        }
    }

    private companion object {
        val CURRENT = ThreadLocal<ProtocolExecutor?>()
    }
}

/** A handle to stop something: a timer, a request, a subscription. */
public fun interface Cancellable {
    /** Idempotent. */
    public fun cancel()

    public companion object {
        /** A handle that does nothing. */
        public val NONE: Cancellable = Cancellable { }
    }
}
