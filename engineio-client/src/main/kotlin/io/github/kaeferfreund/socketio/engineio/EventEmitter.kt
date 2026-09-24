package io.github.kaeferfreund.socketio.engineio

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A small typed event emitter, the counterpart of
 * `@socket.io/component-emitter` for one sealed event hierarchy.
 *
 * Listeners may be added and removed from any thread; [emit] iterates over a
 * snapshot, so a listener removed during an emission may still receive that
 * emission — exactly as JavaScript copies the listener array before calling.
 */
@InternalSocketIOApi
public class EventEmitter<E : Any> {
    private inner class Entry(
        val type: Class<out E>,
        val once: Boolean,
        val listener: (E) -> Unit,
    ) : Cancellable {
        override fun cancel() {
            entries.remove(this)
        }
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    /** Calls [listener] for every event of [type] until cancelled. */
    public fun <T : E> on(
        type: Class<T>,
        listener: (T) -> Unit,
    ): Cancellable = add(type, once = false, listener)

    /** Calls [listener] for the next event of [type] only. */
    public fun <T : E> once(
        type: Class<T>,
        listener: (T) -> Unit,
    ): Cancellable = add(type, once = true, listener)

    private fun <T : E> add(
        type: Class<T>,
        once: Boolean,
        listener: (T) -> Unit,
    ): Cancellable {
        @Suppress("UNCHECKED_CAST")
        val entry = Entry(type, once, listener as (E) -> Unit)
        entries.add(entry)
        return entry
    }

    /** Delivers [event] to the matching listeners, in registration order. */
    public fun emit(event: E) {
        for (entry in entries) {
            if (!entry.type.isInstance(event)) continue
            if (entry.once && !entries.remove(entry)) continue
            entry.listener(event)
        }
    }

    /** Number of listeners for [type]. */
    public fun listenerCount(type: Class<out E>): Int = entries.count { type.isAssignableFrom(it.type) }

    /** Removes every listener, or only those registered for exactly [type]. */
    public fun removeAllListeners(type: Class<out E>? = null) {
        if (type == null) entries.clear() else entries.removeAll { it.type == type }
    }
}

/** Kotlin-friendly [EventEmitter.on]. */
@InternalSocketIOApi
public inline fun <reified T : E, E : Any> EventEmitter<E>.on(noinline listener: (T) -> Unit): Cancellable = on(T::class.java, listener)

/** Kotlin-friendly [EventEmitter.once]. */
@InternalSocketIOApi
public inline fun <reified T : E, E : Any> EventEmitter<E>.once(noinline listener: (T) -> Unit): Cancellable = once(T::class.java, listener)
