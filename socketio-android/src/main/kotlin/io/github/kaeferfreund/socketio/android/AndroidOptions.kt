package io.github.kaeferfreund.socketio.android

import android.content.Context
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.okhttp.OkHttpEngineClients
import io.github.kaeferfreund.socketio.okhttp.TlsPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.WeakHashMap
import kotlin.time.Duration

/** What happens to the connection while the app is in the background (no visible activity). */
public sealed class BackgroundPolicy {
    /** Stay connected; the app decides (for example with a foreground service). */
    public object KeepAlive : BackgroundPolicy()

    /** Pause the connection as soon as the app goes to the background. */
    public object DisconnectImmediately : BackgroundPolicy()

    /**
     * Pause after the app stayed in the background for [delay]; coming back earlier
     * cancels it. `Duration.INFINITE` never pauses.
     */
    public class DisconnectAfter(
        public val delay: Duration,
    ) : BackgroundPolicy() {
        init {
            require(!delay.isNegative()) { "delay must not be negative" }
        }
    }
}

/** Network conditions as reported by `ConnectivityManager` for the default network. */
public class NetworkStatus(
    /** A default network exists. */
    public val available: Boolean,
    /** The network charges by volume (typically mobile data). */
    public val metered: Boolean,
    /** Android validated internet access on the network. */
    public val validated: Boolean,
) {
    override fun toString(): String = "NetworkStatus(available=$available, metered=$metered, validated=$validated)"

    public companion object {
        public val UNKNOWN: NetworkStatus = NetworkStatus(available = true, metered = false, validated = false)
    }
}

/** The Android integration settings; see [android]. */
public class AndroidSocketOptions internal constructor() {
    /**
     * Route sockets and DNS through the current default network and move with it.
     * A VPN default network is left to the system's routing (which already goes
     * through the VPN); a lookup that fails on the bound network is retried with
     * the system resolver.
     */
    public var bindToActiveNetwork: Boolean = true

    /**
     * Pause reconnection while there is no network and reconnect the moment
     * one becomes available, instead of waiting out the backoff delay.
     */
    public var reconnectOnNetworkAvailable: Boolean = true

    /** What to do in the background; default: keep the connection. */
    public var backgroundPolicy: BackgroundPolicy = BackgroundPolicy.KeepAlive

    /** `TrafficStats` tag for this manager's sockets, visible in `NetworkStatsManager`; `null` for none. */
    public var trafficStatsTag: Int? = null

    /** Deliver listener callbacks on the main thread (`Dispatchers.Main.immediate`). */
    public var mainThreadCallbacks: Boolean = true

    /** Logcat tag; logging is gated by `Log.isLoggable(tag, level)`. `null` disables logging. */
    public var logTag: String? = "SocketIO"

    /** Emit Perfetto sections for connect, upgrade and acknowledgement roundtrips. */
    public var tracing: Boolean = false

    /** Release idle pooled connections when the system asks to trim memory. */
    public var releaseConnectionsOnTrimMemory: Boolean = true

    /** Server/client authentication; the default keeps the app's Network Security Config. */
    public var tlsPolicy: TlsPolicy = TlsPolicy.systemDefault()

    /** Base OkHttp client (proxy, interceptors, cookie jar …); a shared default when `null`. */
    public var okHttpClient: OkHttpClient? = null

    internal val okHttpCustomizations = ArrayList<OkHttpClient.Builder.() -> Unit>()

    /**
     * Further OkHttp settings (proxy, interceptors, cookie jar …). Use this
     * instead of the `okHttp { }` builder extension, which would replace the
     * network-bound client this integration installs.
     */
    public fun configureOkHttp(block: OkHttpClient.Builder.() -> Unit) {
        okHttpCustomizations += block
    }
}

/**
 * Configures the manager for Android: elapsed-realtime clock, network
 * callbacks and binding, background policy, logcat, Perfetto sections,
 * traffic tagging, memory trimming and main-thread listener callbacks.
 *
 * ```kotlin
 * val manager = SocketManager(url, SocketManagerOptions {
 *     android(context) {
 *         backgroundPolicy = BackgroundPolicy.DisconnectAfter(30.seconds)
 *         trafficStatsTag = 0x5001
 *     }
 * })
 * ```
 */
public fun SocketManagerOptions.Builder.android(
    context: Context,
    block: AndroidSocketOptions.() -> Unit = {},
) {
    val settings = AndroidSocketOptions().apply(block)
    val appContext = context.applicationContext ?: context
    val base = settings.okHttpClient ?: OkHttpEngineClients.defaultClient
    val binding = NetworkBinding(settings.trafficStatsTag, systemDns = base.dns, defaultSocketFactory = base.socketFactory)
    val builder = base.newBuilder()
    settings.tlsPolicy.applyTo(builder)
    if (settings.bindToActiveNetwork || settings.trafficStatsTag != null) builder.socketFactory(binding.socketFactory)
    if (settings.bindToActiveNetwork) builder.dns(binding.dns)
    // Applied on top of the network binding: a DNS or socket factory set here wins.
    settings.okHttpCustomizations.forEach { builder.it() }
    val clients = OkHttpEngineClients(builder.build())
    this.clients = clients.clients
    timeSource = ElapsedRealtimeTimeSource
    if (settings.mainThreadCallbacks) callbackDispatcher = Dispatchers.Main.immediate
    settings.logTag?.let { logger = AndroidLogger(it) }
    if (settings.tracing) tracer = AndroidTracer
    plugins += NetworkMonitor(appContext, settings, binding, clients.client)
    if (settings.backgroundPolicy !is BackgroundPolicy.KeepAlive) plugins += BackgroundPolicyPlugin(settings.backgroundPolicy)
    if (settings.releaseConnectionsOnTrimMemory) plugins += TrimMemoryPlugin(appContext, clients.client)
}

internal val networkStatusByManager: MutableMap<SocketManager, StateFlow<NetworkStatus>> = Collections.synchronizedMap(WeakHashMap())

/** The default network as seen by the Android integration, or `null` without [android]. */
public val SocketManager.networkStatus: StateFlow<NetworkStatus>?
    get() = networkStatusByManager[this]
