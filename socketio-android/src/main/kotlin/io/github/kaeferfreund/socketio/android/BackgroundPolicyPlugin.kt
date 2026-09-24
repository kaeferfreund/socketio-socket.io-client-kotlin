package io.github.kaeferfreund.socketio.android

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerPlugin
import io.github.kaeferfreund.socketio.engineio.Cancellable

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
        val observer =
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    main.removeCallbacks(pause)
                    manager.resume()
                }

                override fun onStop(owner: LifecycleOwner) {
                    when (policy) {
                        BackgroundPolicy.KeepAlive -> Unit
                        BackgroundPolicy.DisconnectImmediately -> manager.pause()
                        is BackgroundPolicy.DisconnectAfter -> main.postDelayed(pause, policy.delay.inWholeMilliseconds)
                    }
                }
            }
        // Lifecycle observers must be added and removed on the main thread.
        val register = Runnable { lifecycleOwner().lifecycle.addObserver(observer) }
        if (Looper.myLooper() == Looper.getMainLooper()) register.run() else main.post(register)
        return Cancellable {
            main.removeCallbacks(pause)
            val unregister = Runnable { lifecycleOwner().lifecycle.removeObserver(observer) }
            if (Looper.myLooper() == Looper.getMainLooper()) unregister.run() else main.post(unregister)
        }
    }
}
