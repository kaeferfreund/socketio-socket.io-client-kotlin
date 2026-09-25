package io.github.kaeferfreund.socketio.engineio

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** One cookie as `engine.io-client` keeps it: name, value and optional expiry. */
public class EngineCookie(
    public val name: String,
    public val value: String,
    public val expires: Instant? = null,
) {
    override fun equals(other: Any?): Boolean = other is EngineCookie && other.name == name && other.value == value && other.expires == expires

    override fun hashCode(): Int = (name.hashCode() * 31 + value.hashCode()) * 31 + (expires?.hashCode() ?: 0)

    override fun toString(): String = "EngineCookie(name=$name, value=$value, expires=$expires)"

    public companion object {
        private val EXPIRES_FORMATS =
            listOf(
                DateTimeFormatter.RFC_1123_DATE_TIME,
                DateTimeFormatter.ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz", Locale.US),
                DateTimeFormatter.ofPattern("EEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
                DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US),
            )

        /**
         * `parse()` of `engine.io-client`'s Node cookie jar: splits on `"; "`,
         * takes the first `name=value`, strips surrounding quotes from the
         * value and reads `Expires` and `Max-Age` (the later attribute wins).
         * Returns `null` for input without a name.
         */
        public fun parse(
            setCookie: String,
            now: Instant = Instant.now(),
        ): EngineCookie? {
            val parts = setCookie.split("; ")
            val i = parts[0].indexOf('=')
            if (i == -1) return null
            val name = parts[0].substring(0, i).trim()
            if (name.isEmpty()) return null
            var value = parts[0].substring(i + 1).trim()
            if (value.startsWith('"')) value = value.substring(1, maxOf(1, value.length - 1))
            var expires: Instant? = null
            for (j in 1 until parts.size) {
                val sub = parts[j].split("=")
                if (sub.size != 2) continue
                val attribute = sub[1].trim()
                when (sub[0].trim()) {
                    "Expires" -> expires = parseExpires(attribute)
                    "Max-Age" -> expires = attribute.toLongOrNull()?.let { now.plusSeconds(it) }
                }
            }
            return EngineCookie(name, value, expires)
        }

        private fun parseExpires(text: String): Instant? {
            for (format in EXPIRES_FORMATS) {
                try {
                    return ZonedDateTime.parse(text, format).toInstant()
                } catch (e: DateTimeParseException) {
                    // try the next format
                }
            }
            // `new Date(invalid)` never compares as expired in JavaScript.
            return null
        }
    }
}

/**
 * The per-connection cookie jar `engine.io-client` creates when
 * `withCredentials` is enabled outside a browser. Cookies from every
 * `Set-Cookie` response header are replayed on later polling requests and on
 * the WebSocket handshake of the same engine. Used only on the protocol executor.
 */
public class EngineCookieJar(
    private val clock: () -> Instant = Instant::now,
) {
    private val cookies = LinkedHashMap<String, EngineCookie>()

    /**
     * Stores every parseable cookie; a later cookie replaces one with the same name.
     * A cookie whose name or value holds anything but printable ASCII is ignored:
     * RFC 6265 cookie octets are ASCII, and such a value could not be sent back in
     * a request header (Node's HTTP client fails the request instead).
     */
    public fun parseCookies(setCookieHeaders: List<String>) {
        for (header in setCookieHeaders) {
            val cookie = EngineCookie.parse(header, clock()) ?: continue
            if (!cookie.name.isPrintableAscii() || !cookie.value.isPrintableAscii()) continue
            cookies[cookie.name] = cookie
        }
    }

    private fun String.isPrintableAscii(): Boolean = all { it in ' '..'~' }

    /** The unexpired cookies, dropping expired ones. */
    public val current: List<EngineCookie>
        get() {
            val now = clock()
            cookies.values.removeAll { cookie -> cookie.expires?.isBefore(now) == true }
            return cookies.values.toList()
        }

    /** The `Cookie` request header value, or `null` when the jar is empty. */
    public fun cookieHeader(): String? = current.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
}
