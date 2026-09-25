package io.github.kaeferfreund.socketio.parser

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Thrown by [SocketIOJson.parse] for text that `JSON.parse` would reject, or that exceeds a limit. */
public class SocketIOJsonException(
    message: String,
) : IllegalArgumentException(message)

/**
 * JSON text codec with `JSON.parse`/`JSON.stringify` semantics.
 *
 * Both directions run on an explicit stack: nesting depth is limited only by
 * the configured `maxDepth`, never by the thread's stack size.
 */
public object SocketIOJson {
    /**
     * Parses [text] like `JSON.parse`.
     *
     * @param maxDepth maximum array/object nesting; deeper input is rejected.
     * @param reviver optional `JSON.parse` reviver. It is called bottom-up for
     *   every member and element with its key (array indices as decimal
     *   strings) and finally for the root with key `""`. Returning `null`
     *   removes an object member, or turns an array element into `null`, as
     *   JavaScript does for `undefined`.
     */
    @JvmStatic
    @JvmOverloads
    public fun parse(
        text: String,
        maxDepth: Int = Int.MAX_VALUE,
        reviver: ((key: String, value: SocketIOValue) -> SocketIOValue?)? = null,
    ): SocketIOValue {
        val value = JsonReader(text, maxDepth).read()
        return if (reviver == null) value else revive(value, reviver)
    }

    /**
     * Serializes [value] like `JSON.stringify`. Binary values have no JSON form;
     * they are written as the text `"<binary N bytes>"`, for logs and
     * `toString()`. The Socket.IO encoder replaces them with attachment
     * placeholders instead.
     */
    @JvmStatic
    public fun stringify(value: SocketIOValue): String = stringify(value, binaryAsPlaceholderText = true)

    internal fun stringify(
        value: SocketIOValue,
        binaryAsPlaceholderText: Boolean,
    ): String {
        val out = StringBuilder()
        // Work items: either a value to write or a literal to append.
        val stack = ArrayDeque<Any>()
        stack.addLast(value)
        while (stack.isNotEmpty()) {
            when (val item = stack.removeLast()) {
                is String -> out.append(item)

                is SocketIOValue.Null -> out.append("null")

                is SocketIOValue.Bool -> out.append(item.value)

                is SocketIOValue.Number -> out.append(formatNumber(item))

                is SocketIOValue.Text -> quote(item.value, out)

                is SocketIOValue.Binary -> {
                    require(binaryAsPlaceholderText) { "binary data cannot be written as JSON" }
                    quote("<binary ${item.size} bytes>", out)
                }

                is SocketIOValue.Array -> {
                    out.append('[')
                    stack.addLast("]")
                    for (i in item.items.indices.reversed()) {
                        stack.addLast(item.items[i])
                        if (i > 0) stack.addLast(",")
                    }
                }

                is SocketIOValue.Object -> {
                    out.append('{')
                    stack.addLast("}")
                    val entries = item.fields.entries.toList()
                    for (i in entries.indices.reversed()) {
                        stack.addLast(entries[i].value)
                        stack.addLast(QuotedKey(entries[i].key))
                        if (i > 0) stack.addLast(",")
                    }
                }

                is QuotedKey -> {
                    quote(item.key, out)
                    out.append(':')
                }

                else -> error("unexpected work item")
            }
        }
        return out.toString()
    }

    private class QuotedKey(
        val key: String,
    )

    /** Writes [text] as a JSON string literal exactly like `JSON.stringify`. */
    internal fun quote(
        text: String,
        out: StringBuilder,
    ) {
        out.append('"')
        var i = 0
        val length = text.length
        while (i < length) {
            val c = text[i]
            when {
                c == '"' -> out.append("\\\"")

                c == '\\' -> out.append("\\\\")

                c == '\b' -> out.append("\\b")

                c == '\u000c' -> out.append("\\f")

                c == '\n' -> out.append("\\n")

                c == '\r' -> out.append("\\r")

                c == '\t' -> out.append("\\t")

                c < ' ' -> appendUnicodeEscape(c, out)

                Character.isHighSurrogate(c) -> {
                    if (i + 1 < length && Character.isLowSurrogate(text[i + 1])) {
                        out.append(c).append(text[i + 1])
                        i++
                    } else {
                        appendUnicodeEscape(c, out)
                    }
                }

                Character.isLowSurrogate(c) -> appendUnicodeEscape(c, out)

                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun appendUnicodeEscape(
        c: Char,
        out: StringBuilder,
    ) {
        val hex = c.code.toString(16)
        out.append("\\u")
        repeat(4 - hex.length) { out.append('0') }
        out.append(hex)
    }

    /** Formats a number like JavaScript's `Number.prototype.toString()`; non-finite values become `null`. */
    internal fun formatNumber(number: SocketIOValue.Number): String {
        val value = number.value
        if (value is Long) return value.toString()
        return formatDouble(value.toDouble())
    }

    /** `JSON.stringify` of a JavaScript number. */
    public fun formatDouble(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "null"
        if (value == 0.0) return "0"
        val negative = value < 0
        val magnitude = kotlin.math.abs(value)
        // Number::toString: the fewest digits k that read back as the same double; among the
        // k-digit candidates (at most the neighbours below and above), the closest, then the even.
        val exact = BigDecimal(magnitude)
        var digits = ""
        var exponent = 0
        for (precision in 1..17) {
            val below = exact.round(MathContext(precision, RoundingMode.FLOOR))
            val above = exact.round(MathContext(precision, RoundingMode.CEILING))
            val chosen =
                listOf(below, above)
                    .filter { it.toDouble() == magnitude }
                    .minWithOrNull(compareBy<BigDecimal> { it.subtract(exact).abs() }.thenBy { it.unscaledValue().testBit(0) })
                    ?: continue
            digits = chosen.unscaledValue().toString().trimEnd('0').ifEmpty { "0" }
            // value = 0.d1d2... * 10^n  ⇔  n = digitsBeforeScale - scale
            exponent = chosen.precision() - chosen.scale()
            break
        }
        val k = digits.length
        val n = exponent
        val body =
            when {
                n in k..21 -> digits + "0".repeat(n - k)

                n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)

                n in -5..0 -> "0." + "0".repeat(-n) + digits

                else -> {
                    val e = n - 1
                    val sign = if (e < 0) "-" else "+"
                    val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                    mantissa + "e" + sign + kotlin.math.abs(e)
                }
            }
        return if (negative) "-$body" else body
    }

    /**
     * JavaScript `Number(string)` (the StringToNumber abstract operation):
     * surrounding whitespace is ignored, the empty string is `0`, and
     * `0x`/`0o`/`0b` prefixes, `Infinity` and decimal literals are accepted.
     * Everything else is `NaN`.
     */
    public fun javaScriptNumber(text: String): Double {
        val s = text.trim(::isJavaScriptWhitespace)
        if (s.isEmpty()) return 0.0
        if (s.length > 2 && s[0] == '0') {
            val radix =
                when (s[1]) {
                    'x', 'X' -> 16
                    'o', 'O' -> 8
                    'b', 'B' -> 2
                    else -> 0
                }
            if (radix != 0) {
                var result = 0.0
                for (i in 2 until s.length) {
                    val d = asciiDigit(s[i], radix)
                    if (d < 0) return Double.NaN
                    result = result * radix + d
                }
                return result
            }
        }
        val negative = s[0] == '-'
        val unsigned = if (s[0] == '-' || s[0] == '+') s.substring(1) else s
        if (unsigned == "Infinity") return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        if (!DECIMAL_LITERAL.matches(unsigned)) return Double.NaN
        val parsed = unsigned.toDouble()
        return if (negative) -parsed else parsed
    }

    /** The value of an ASCII digit or letter in [radix], else -1; `Character.digit` would accept any Unicode digit. */
    internal fun asciiDigit(
        c: Char,
        radix: Int,
    ): Int {
        val value =
            when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 10
                else -> return -1
            }
        return if (value < radix) value else -1
    }

    private val DECIMAL_LITERAL = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    /** ECMAScript WhiteSpace and LineTerminator code points. */
    public fun isJavaScriptWhitespace(c: Char): Boolean =
        when (c) {
            '\t', '\n', '\u000b', '\u000c', '\r', ' ', '\u00a0', '\u1680', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff' -> true
            else -> c in '\u2000'..'\u200a'
        }

    private fun revive(
        root: SocketIOValue,
        reviver: (String, SocketIOValue) -> SocketIOValue?,
    ): SocketIOValue {
        // InternalizeJSONProperty: each member is revived as soon as its own
        // members are, so siblings are visited in order and the root last.
        class Frame(
            val value: SocketIOValue,
            val key: String,
        ) {
            val keys: List<String> =
                when (value) {
                    is SocketIOValue.Array -> value.items.indices.map { it.toString() }
                    is SocketIOValue.Object -> value.fields.keys.toList()
                    else -> emptyList()
                }
            val children: List<SocketIOValue> =
                when (value) {
                    is SocketIOValue.Array -> value.items
                    is SocketIOValue.Object -> value.fields.values.toList()
                    else -> emptyList()
                }
            var index = 0
            val revived = ArrayList<SocketIOValue?>(children.size)
        }
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, ""))
        var result: SocketIOValue? = null
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.index < frame.children.size) {
                val i = frame.index++
                stack.addLast(Frame(frame.children[i], frame.keys[i]))
                continue
            }
            stack.removeLast()
            val rebuilt: SocketIOValue =
                when (frame.value) {
                    is SocketIOValue.Array -> SocketIOValue.Array(frame.revived.map { it ?: SocketIOValue.Null })

                    is SocketIOValue.Object -> {
                        val map = LinkedHashMap<String, SocketIOValue>()
                        frame.revived.forEachIndexed { i, child -> if (child != null) map[frame.keys[i]] = child }
                        SocketIOValue.Object(map)
                    }

                    else -> frame.value
                }
            val revived = reviver(frame.key, rebuilt)
            val parent = stack.lastOrNull()
            if (parent == null) result = revived else parent.revived.add(revived)
        }
        return result ?: SocketIOValue.Null
    }
}

/** An iterative `JSON.parse` reader. */
private class JsonReader(
    private val text: String,
    private val maxDepth: Int,
) {
    private var pos = 0

    private sealed class Container {
        class ArrayBuilder : Container() {
            val items = ArrayList<SocketIOValue>()
        }

        class ObjectBuilder : Container() {
            val fields = LinkedHashMap<String, SocketIOValue>()
            var key: String? = null
        }
    }

    // The iterative JSON reader: one loop over the grammar instead of recursion.
    @Suppress("CyclomaticComplexMethod")
    fun read(): SocketIOValue {
        val stack = ArrayDeque<Container>()
        var result: SocketIOValue? = null
        skipWhitespace()
        loop@ while (true) {
            // Read one value start.
            var value: SocketIOValue? = null
            when (peek()) {
                '{' -> {
                    pos++
                    if (stack.size + 1 > maxDepth) fail("maximum nesting depth $maxDepth exceeded")
                    skipWhitespace()
                    if (peek() == '}') {
                        pos++
                        value = SocketIOValue.Object(emptyMap())
                    } else {
                        val builder = Container.ObjectBuilder()
                        builder.key = readKey()
                        stack.addLast(builder)
                        skipWhitespace()
                        continue@loop
                    }
                }

                '[' -> {
                    pos++
                    if (stack.size + 1 > maxDepth) fail("maximum nesting depth $maxDepth exceeded")
                    skipWhitespace()
                    if (peek() == ']') {
                        pos++
                        value = SocketIOValue.Array(emptyList())
                    } else {
                        stack.addLast(Container.ArrayBuilder())
                        continue@loop
                    }
                }

                '"' -> value = SocketIOValue.Text(readString())

                't' -> value = literal("true", SocketIOValue.Bool.TRUE)

                'f' -> value = literal("false", SocketIOValue.Bool.FALSE)

                'n' -> value = literal("null", SocketIOValue.Null)

                else -> value = readNumber()
            }
            // Attach the complete value, closing containers as they finish.
            var complete: SocketIOValue = value
            while (true) {
                val top = stack.lastOrNull()
                if (top == null) {
                    result = complete
                    break@loop
                }
                skipWhitespace()
                when (top) {
                    is Container.ArrayBuilder -> {
                        top.items.add(complete)
                        when (next()) {
                            ',' -> {
                                skipWhitespace()
                                continue@loop
                            }

                            ']' -> {
                                stack.removeLast()
                                complete = SocketIOValue.Array(top.items)
                            }

                            else -> fail("expected ',' or ']'")
                        }
                    }

                    is Container.ObjectBuilder -> {
                        top.fields[top.key!!] = complete
                        when (next()) {
                            ',' -> {
                                skipWhitespace()
                                top.key = readKey()
                                skipWhitespace()
                                continue@loop
                            }

                            '}' -> {
                                stack.removeLast()
                                complete = SocketIOValue.Object(top.fields)
                            }

                            else -> fail("expected ',' or '}'")
                        }
                    }
                }
            }
        }
        skipWhitespace()
        if (pos != text.length) fail("unexpected trailing input")
        return result
    }

    private fun readKey(): String {
        if (peek() != '"') fail("expected a string key")
        val key = readString()
        skipWhitespace()
        if (next() != ':') fail("expected ':'")
        skipWhitespace()
        return key
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else fail("unexpected end of input")

    private fun next(): Char = peek().also { pos++ }

    private fun skipWhitespace() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun literal(
        word: String,
        value: SocketIOValue,
    ): SocketIOValue {
        if (!text.startsWith(word, pos)) fail("unexpected token")
        pos += word.length
        return value
    }

    // Every JSON escape is one branch.
    @Suppress("CyclomaticComplexMethod")
    private fun readString(): String {
        pos++ // opening quote
        val start = pos
        // Fast path: no escapes.
        while (pos < text.length) {
            val c = text[pos]
            if (c == '"') {
                return text.substring(start, pos).also { pos++ }
            }
            if (c == '\\' || c < ' ') break
            pos++
        }
        val out = StringBuilder().append(text, start, pos)
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos++]
            when {
                c == '"' -> return out.toString()

                c < ' ' -> fail("control character in string")

                c == '\\' -> {
                    if (pos >= text.length) fail("unterminated escape")
                    when (text[pos++]) {
                        '"' -> out.append('"')

                        '\\' -> out.append('\\')

                        '/' -> out.append('/')

                        'b' -> out.append('\b')

                        'f' -> out.append('\u000c')

                        'n' -> out.append('\n')

                        'r' -> out.append('\r')

                        't' -> out.append('\t')

                        'u' -> {
                            if (pos + 4 > text.length) fail("bad unicode escape")
                            var code = 0
                            for (i in 0 until 4) {
                                val d = SocketIOJson.asciiDigit(text[pos + i], 16)
                                if (d < 0) fail("bad unicode escape")
                                code = code * 16 + d
                            }
                            pos += 4
                            out.append(code.toChar())
                        }

                        else -> fail("bad escape")
                    }
                }

                else -> out.append(c)
            }
        }
    }

    // The JSON number grammar, branch by branch.
    @Suppress("CyclomaticComplexMethod")
    private fun readNumber(): SocketIOValue {
        val start = pos
        if (pos < text.length && text[pos] == '-') pos++
        if (pos >= text.length) fail("unexpected end of input")
        when (text[pos]) {
            '0' -> pos++
            in '1'..'9' -> while (pos < text.length && text[pos] in '0'..'9') pos++
            else -> fail("unexpected token")
        }
        var integral = true
        if (pos < text.length && text[pos] == '.') {
            integral = false
            pos++
            val digits = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos == digits) fail("expected digits after '.'")
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            integral = false
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            val digits = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos == digits) fail("expected exponent digits")
        }
        val literal = text.substring(start, pos)
        if (integral && literal.length <= 19) {
            literal.toLongOrNull()?.let { return SocketIOValue.Number.of(it) }
        }
        return SocketIOValue.Number.of(literal.toDouble())
    }

    private fun fail(message: String): Nothing = throw SocketIOJsonException("$message at position $pos")
}
