package io.github.kaeferfreund.socketio.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerPlugin
import io.github.kaeferfreund.socketio.engineio.Cancellable
import io.github.kaeferfreund.socketio.engineio.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

/**
 * Follows the default network with `registerDefaultNetworkCallback`:
 *
 * - a new default network re-binds the manager and drops connections on the
 *   old one, so they reconnect at once instead of waiting for a ping timeout;
 * - losing the network closes the connection with `"transport close"` right
 *   away and holds reconnection attempts until a network is back;
 * - capability changes publish [NetworkStatus] (metered, validated).
 */
internal class NetworkMonitor(
    private val context: Context,
    private val settings: AndroidSocketOptions,
    private val binding: NetworkBinding,
    private val client: OkHttpClient,
) : SocketManagerPlugin {
    override fun attach(manager: SocketManager): Cancellable {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return Cancellable.NONE
        val status = MutableStateFlow(NetworkStatus.UNKNOWN)
        networkStatusByManager[manager] = status
        val logger = manager.options.logger
        var current: Network? = connectivity.activeNetwork
        if (settings.bindToActiveNetwork) binding.network = current
        if (current == null) {
            status.value = NetworkStatus(available = false, metered = false, validated = false)
            if (settings.reconnectOnNetworkAvailable) manager.setNetworkAvailable(false)
        }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val previous = current
                    current = network
                    if (settings.bindToActiveNetwork) binding.network = network
                    if (previous != null && previous != network) {
                        if (logger.isLoggable(LogLevel.INFO)) logger.log(LogLevel.INFO, "network", "default network changed; reconnecting on the new one")
                        client.connectionPool.evictAll()
                        manager.onNetworkLost()
                        // Reconnect on the new network now rather than after the backoff delay.
                        if (settings.reconnectOnNetworkAvailable) manager.reconnectNow()
                    }
                    status.value = NetworkStatus(true, status.value.metered, status.value.validated)
                    if (settings.reconnectOnNetworkAvailable) manager.setNetworkAvailable(true)
                }

                override fun onLost(network: Network) {
                    if (network != current) return
                    current = null
                    if (settings.bindToActiveNetwork) binding.network = null
                    status.value = NetworkStatus(available = false, metered = false, validated = false)
                    if (logger.isLoggable(LogLevel.INFO)) logger.log(LogLevel.INFO, "network", "default network lost")
                    client.connectionPool.evictAll()
                    if (settings.reconnectOnNetworkAvailable) manager.setNetworkAvailable(false)
                    manager.onNetworkLost()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    if (network != current) return
                    status.value =
                        NetworkStatus(
                            available = true,
                            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        )
                }
            }
        connectivity.registerDefaultNetworkCallback(callback)
        return Cancellable {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
            networkStatusByManager.remove(manager)
        }
    }
}
