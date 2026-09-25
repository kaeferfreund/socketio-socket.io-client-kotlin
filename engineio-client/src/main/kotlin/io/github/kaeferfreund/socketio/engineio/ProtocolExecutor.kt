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
import kotlinx.coroutines.launch
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

    /** Runs [block] now if already on this executor, otherwise queues it. */
    public fun execute(block: () -> Unit) {
        if (isCurrent) block() else post(block)
    }

    /** Always queues [block], even from this executor (JavaScript `nextTick`). */
    public fun post(block: () -> Unit) {
        scope.launch { block() }
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
                block()
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
