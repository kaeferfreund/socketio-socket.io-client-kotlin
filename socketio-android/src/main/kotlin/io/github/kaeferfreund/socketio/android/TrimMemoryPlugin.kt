package io.github.kaeferfreund.socketio.android

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerPlugin
import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.LogLevel
import okhttp3.OkHttpClient

/**
 * Releases idle pooled connections when the system is low on memory
 * (`onTrimMemory` at `TRIM_MEMORY_BACKGROUND` or above). Buffered emits and
 * received events are never dropped here: bound them with
 * `SocketBufferLimits` instead.
 */
internal class TrimMemoryPlugin(
    private val context: Context,
    private val client: OkHttpClient,
) : SocketManagerPlugin {
    override fun attach(manager: SocketManager): Cancellable {
        val callbacks =
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    @Suppress("DEPRECATION")
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                        client.connectionPool.evictAll()
                        val logger = manager.options.logger
                        if (logger.isLoggable(LogLevel.DEBUG)) logger.log(LogLevel.DEBUG, "memory", "released idle connections (trim level $level)")
                    }
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    client.connectionPool.evictAll()
                }
            }
        context.registerComponentCallbacks(callbacks)
        return Cancellable { context.unregisterComponentCallbacks(callbacks) }
    }
}
