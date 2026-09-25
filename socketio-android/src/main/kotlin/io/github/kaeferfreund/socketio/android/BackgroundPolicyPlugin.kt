package io.github.kaeferfreund.socketio.android

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerPlugin
import io.github.kaeferfreund.socketio.engineio.Cancellable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies a [BackgroundPolicy] with `ProcessLifecycleOwner`: in the
 * background the manager is paused ([SocketManager.pause], sockets stay
 * active), in the foreground it resumes and the sockets reconnect — with
 * connection state recovery when the server supports it.
 */
internal class BackgroundPolicyPlugin(
    private val policy: BackgroundPolicy,
    private val lifecycleOwner: () -> LifecycleOwner = { ProcessLifecycleOwner.get() },
) : SocketManagerPlugin {
    override fun attach(manager: SocketManager): Cancellable {
        val main = Handler(Looper.getMainLooper())
        val pause = Runnable { manager.pause() }
        val inBackground = {
            when (policy) {
                BackgroundPolicy.KeepAlive -> Unit

                BackgroundPolicy.DisconnectImmediately -> manager.pause()

                // Duration.INFINITE never pauses; Long.MAX_VALUE would overflow the handler's clock.
                is BackgroundPolicy.DisconnectAfter ->
                    if (policy.delay.isFinite()) main.postDelayed(pause, policy.delay.inWholeMilliseconds.coerceAtMost(MAX_DELAY_MILLIS))
            }
        }
        val observer =
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    main.removeCallbacks(pause)
                    manager.resume()
                }

                override fun onStop(owner: LifecycleOwner) = inBackground()
            }
        val cancelled = AtomicBoolean(false)
        // Lifecycle observers must be added and removed on the main thread.
        val register =
            Runnable {
                // close() may already have run on the main thread while this was queued.
                if (cancelled.get()) return@Runnable
                val lifecycle = lifecycleOwner().lifecycle
                lifecycle.addObserver(observer)
                // addObserver replays only the states reached: a manager created while the app is in
                // the background (started for a push or a job) never sees onStop, so apply the policy now.
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) inBackground()
            }
        if (Looper.myLooper() == Looper.getMainLooper()) register.run() else main.post(register)
        return Cancellable {
            cancelled.set(true)
            main.removeCallbacks(pause)
            val unregister = Runnable { lifecycleOwner().lifecycle.removeObserver(observer) }
            if (Looper.myLooper() == Looper.getMainLooper()) unregister.run() else main.post(unregister)
        }
    }

    private companion object {
        /** A year: far beyond any background delay, far from overflowing `uptimeMillis() + delay`. */
        const val MAX_DELAY_MILLIS = 365L * 24 * 60 * 60 * 1000
    }
}
