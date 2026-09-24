package io.github.kaeferfreund.socketio.okhttp

import io.github.kaeferfreund.socketio.SocketManagerOptions
import okhttp3.OkHttpClient

/** Configures the OkHttp stack of a manager; see [okHttp]. */
public class OkHttpClientsBuilder internal constructor() {
    /** The base client; a shared default when `null`. Its connection pool and dispatcher are reused. */
    public var client: OkHttpClient? = null

    /** Server (and client) authentication. */
    public var tlsPolicy: TlsPolicy = TlsPolicy.systemDefault()

    private val customizations = ArrayList<OkHttpClient.Builder.() -> Unit>()

    /** Further OkHttp settings: proxy, DNS, socket factory, cookie jar, interceptors … */
    public fun configure(block: OkHttpClient.Builder.() -> Unit) {
        customizations += block
    }

    internal fun build(): OkHttpEngineClients {
        val builder = (client ?: OkHttpEngineClients.defaultClient).newBuilder()
        tlsPolicy.applyTo(builder)
        customizations.forEach { builder.it() }
        return OkHttpEngineClients(builder.build())
    }
}

/**
 * Uses OkHttp with the given settings for this manager's transports.
 *
 * ```kotlin
 * SocketManagerOptions {
 *     okHttp {
 *         tlsPolicy = TlsPolicy.pinned("api.example.com", "sha256/AAAA…")
 *         configure { proxy(Proxy.NO_PROXY) }
 *     }
 * }
 * ```
 *
 * Without this call the default OkHttp stack is discovered automatically.
 */
public fun SocketManagerOptions.Builder.okHttp(block: OkHttpClientsBuilder.() -> Unit) {
    clients = OkHttpClientsBuilder().apply(block).build().clients
}
