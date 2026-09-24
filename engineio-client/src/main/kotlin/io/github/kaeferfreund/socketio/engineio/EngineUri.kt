package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import kotlin.math.ceil

/**
 * URI and query helpers with the exact behaviour of `engine.io-client`
 * (`contrib/parseuri.ts`, `contrib/parseqs.ts`, `util.ts`).
 */
public object EngineUri {
    /** The parts of a URI that `engine.io-client` uses. */
    public class Parsed internal constructor(
        /** `http`, `https`, `ws`, `wss` or empty when the input had no scheme. */
        public val protocol: String,
        /** Host name; IPv6 addresses without brackets. */
        public val host: String,
        /** Port as written, or empty. */
        public val port: String,
        /** Path, possibly empty. */
        public val path: String,
        /** Raw query string without `?`, or empty. */
        public val query: String,
        /** `true` when the host is an IPv6 literal. */
        public val ipv6: Boolean,
    ) {
        /** `true` for `https` and `wss`. */
        public val secure: Boolean get() = protocol == "https" || protocol == "wss"

        override fun toString(): String = "Parsed(protocol=$protocol, host=$host, port=$port, path=$path, query=$query)"
    }

    private val PARSE_URI =
        Regex(
            "^(?:(?![^:@/?#]+:[^:@/]*@)(http|https|ws|wss)://)?((?:(([^:@/?#]*)(?::([^:@/?#]*))?)?@)?" +
                "((?:[a-f0-9]{0,4}:){2,7}[a-f0-9]{0,4}|[^:/?#]*)(?::(\\d*))?)(((/(?:[^?#](?![^?#/]*\\.[^?#/.]+(?:[?#]|$)))*/?)?" +
                "([^?#/]*))(?:\\?([^#]*))?(?:#(.*))?)",
        )

    /**
     * `parse()` of `engine.io-client`: a regex-based parser that accepts the
     * same relaxed inputs (missing scheme, IPv6 hosts in brackets).
     */
    public fun parse(uri: String): Parsed {
        require(uri.length <= 8000) { "URI too long" }
        var str = uri
        val b = str.indexOf('[')
        val e = str.indexOf(']')
        val bracketed = b != -1 && e != -1
        if (bracketed) {
            str = str.substring(0, b) + str.substring(b, e).replace(':', ';') + str.substring(e)
        }
        val m = PARSE_URI.find(str) ?: error("unparseable URI")
        fun group(i: Int) = m.groups[i]?.value.orEmpty()
        var host = group(6)
        if (bracketed) host = host.substring(1, host.length - 1).replace(';', ':')
        return Parsed(
            protocol = group(1),
            host = host,
            port = group(7),
            path = group(9),
            query = group(12),
            ipv6 = bracketed,
        )
    }

    /** JavaScript `encodeURIComponent`. */
    public fun encodeURIComponent(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val c = byte.toInt() and 0xff
            if (c < 0x80 && isUnreserved(c.toChar())) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
            }
        }
        return out.toString()
    }

    /** JavaScript `decodeURIComponent`, lenient on malformed escapes. */
    public fun decodeURIComponent(value: String): String =
        try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            value
        }

    private const val HEX = "0123456789ABCDEF"

    private fun isUnreserved(c: Char): Boolean = (c.isLetterOrDigit() && c.code < 128) || c in "-_.!~*'()"

    /** `parseqs.encode`: `key=value` pairs joined with `&`, both URI-component encoded, in insertion order. */
    public fun encodeQuery(query: Map<String, String>): String =
        query.entries.joinToString("&") { (key, value) -> encodeURIComponent(key) + "=" + encodeURIComponent(value) }

    /** `parseqs.decode`: splits on `&` and `=`; a key without value maps to `"undefined"` as in JavaScript. */
    public fun decodeQuery(query: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            val parts = pair.split('=')
            result[decodeURIComponent(parts[0])] = if (parts.size > 1) decodeURIComponent(parts[1]) else "undefined"
        }
        return result
    }

    private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

    /**
     * `randomString()`: part of the current time in base 36 plus three random
     * base-36 characters, used as the cache-busting timestamp parameter.
     */
    public fun randomString(nowMillis: Long = System.currentTimeMillis()): String {
        val time = java.lang.Long.toString(nowMillis, 36)
        val random = CharArray(3) { BASE36[java.util.concurrent.ThreadLocalRandom.current().nextInt(36)] }
        return time.substring(minOf(3, time.length)) + String(random)
    }

    /**
     * `byteLength()` of `engine.io-client`: UTF-8 length of text, and the
     * Base64-inflated length (×1.33, rounded up) of binary data.
     */
    public fun byteLength(data: EngineIOData): Long =
        when (data) {
            is EngineIOData.Text -> utf8Length(data.value)
            is EngineIOData.Binary -> ceil(data.size * 1.33).toLong()
        }

    /** UTF-8 length as `engine.io-client` computes it (a lone surrogate counts 3 bytes). */
    public fun utf8Length(text: String): Long {
        var length = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i].code
            length +=
                when {
                    c < 0x80 -> 1

                    c < 0x800 -> 2

                    c < 0xd800 || c >= 0xe000 -> 3

                    else -> {
                        i++
                        4
                    }
                }
            i++
        }
        return length
    }
}
