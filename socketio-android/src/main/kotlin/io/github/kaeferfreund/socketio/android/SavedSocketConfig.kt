package io.github.kaeferfreund.socketio.android

import android.os.Bundle
import io.github.kaeferfreund.socketio.SocketManagerOptions

/**
 * The part of a manager's configuration that can be saved in a [Bundle]
 * (for example with `SavedStateRegistry`) and restored after process death:
 * the URL and plain options. Session state (ids, buffers, acknowledgements)
 * is deliberately not saved — a restored app starts a new connection.
 *
 * Do not store credentials in [query] or [extraHeaders] here; provide them
 * again from secure storage when restoring (for example with an `AuthProvider`).
 */
public class SavedSocketConfig(
    public val uri: String,
    public val path: String = "/socket.io",
    public val transports: List<String> = listOf("polling", "websocket"),
    public val query: Map<String, String> = emptyMap(),
    public val extraHeaders: Map<String, String> = emptyMap(),
    public val namespaces: List<String> = listOf("/"),
) {
    /** Writes this configuration into a new bundle. */
    public fun toBundle(): Bundle =
        Bundle().apply {
            putString(KEY_URI, uri)
            putString(KEY_PATH, path)
            putStringArrayList(KEY_TRANSPORTS, ArrayList(transports))
            putBundle(KEY_QUERY, query.toBundle())
            putBundle(KEY_HEADERS, extraHeaders.toBundle())
            putStringArrayList(KEY_NAMESPACES, ArrayList(namespaces))
        }

    /** Applies the saved options to a builder. */
    public fun applyTo(builder: SocketManagerOptions.Builder) {
        builder.path = path
        builder.transports = transports
        builder.query = query
        builder.extraHeaders = extraHeaders
    }

    public companion object {
        private const val KEY_URI = "socketio.uri"
        private const val KEY_PATH = "socketio.path"
        private const val KEY_TRANSPORTS = "socketio.transports"
        private const val KEY_QUERY = "socketio.query"
        private const val KEY_HEADERS = "socketio.headers"
        private const val KEY_NAMESPACES = "socketio.namespaces"

        /** Reads a configuration written by [toBundle], or `null` if [bundle] holds none. */
        @JvmStatic
        public fun fromBundle(bundle: Bundle?): SavedSocketConfig? {
            val uri = bundle?.getString(KEY_URI) ?: return null
            return SavedSocketConfig(
                uri = uri,
                path = bundle.getString(KEY_PATH) ?: "/socket.io",
                transports = bundle.getStringArrayList(KEY_TRANSPORTS) ?: listOf("polling", "websocket"),
                query = bundle.getBundle(KEY_QUERY).toMap(),
                extraHeaders = bundle.getBundle(KEY_HEADERS).toMap(),
                namespaces = bundle.getStringArrayList(KEY_NAMESPACES) ?: listOf("/"),
            )
        }

        private fun Map<String, String>.toBundle(): Bundle = Bundle().also { bundle -> forEach { (key, value) -> bundle.putString(key, value) } }

        private fun Bundle?.toMap(): Map<String, String> {
            val bundle = this ?: return emptyMap()
            return bundle.keySet().sorted().associateWith { bundle.getString(it).orEmpty() }
        }
    }
}
