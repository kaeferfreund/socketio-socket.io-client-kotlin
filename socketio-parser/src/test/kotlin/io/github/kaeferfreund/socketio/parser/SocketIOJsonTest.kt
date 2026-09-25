package io.github.kaeferfreund.socketio.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `JSON.parse`/`JSON.stringify` semantics of the value codec. Expected strings were produced by Node 24. */
class SocketIOJsonTest {
    // Number::toString picks the k-digit decimal that round-trips, not only the nearest one:
    // at a power of two the rounding interval is asymmetric (checked against Node for 280,140 values).
    @Test
    fun formatsPowersOfTwoWithTheShortestDigitsLikeJavaScript() {
        assertEquals("6.189700196426902e+26", SocketIOJson.formatDouble(Math.pow(2.0, 89.0)))
        assertEquals("5.758609657015292e+163", SocketIOJson.formatDouble(Math.pow(2.0, 544.0)))
        assertEquals("9007199254740992", SocketIOJson.formatDouble(Math.pow(2.0, 53.0)))
    }

    @Test
    fun acceptsOnlyAsciiDigitsLikeJavaScript() {
        // Fullwidth digits after \u and in 0x literals: JSON.parse and Number() reject them.
        assertThrows<SocketIOJsonException> { SocketIOJson.parse("\"\\u\uFF10\uFF10\uFF14\uFF11\"") }
        assertTrue(SocketIOJson.javaScriptNumber("0x\uFF11").isNaN())
        assertEquals("A", SocketIOJson.parse("\"\\u0041\"").string)
        assertEquals(26.0, SocketIOJson.javaScriptNumber("0x1A"))
    }

    @Test
    fun writesBinaryAsPlaceholderTextAsDocumented() {
        assertEquals("{\"k\":\"<binary 1 bytes>\"}", SocketIOJson.stringify(socketIOObject("k" to byteArrayOf(1))))
    }

    @Test
    fun formatsNumbersLikeJavaScript() {
        val cases =
            mapOf(
                0.0 to "0",
                -0.0 to "0",
                1.0 to "1",
                1.5 to "1.5",
                -1.5 to "-1.5",
                0.1 to "0.1",
                0.1 + 0.2 to "0.30000000000000004",
                1e21 to "1e+21",
                1e20 to "100000000000000000000",
                123456789012345680000.0 to "123456789012345680000",
                1e-6 to "0.000001",
                1e-7 to "1e-7",
                1.5e-7 to "1.5e-7",
                5e-324 to "5e-324",
                Double.MAX_VALUE to "1.7976931348623157e+308",
                2.5e25 to "2.5e+25",
                123.456 to "123.456",
                Double.NaN to "null",
                Double.POSITIVE_INFINITY to "null",
                Double.NEGATIVE_INFINITY to "null",
                9007199254740993.0 to "9007199254740992",
            )
        for ((value, expected) in cases) {
            assertEquals(expected, SocketIOJson.stringify(SocketIOValue.Number.of(value)), "for $value")
        }
    }

    @Test
    fun keepsIntegersExactAsLongs() {
        assertEquals(SocketIOValue.Number.of(1L), SocketIOValue.Number.of(1.0))
        assertTrue(SocketIOValue.Number.of(1.0).isIntegral)
        assertEquals("9007199254740993", SocketIOJson.stringify(SocketIOJson.parse("9007199254740993")))
        assertEquals("-9223372036854775808", SocketIOJson.stringify(SocketIOJson.parse("-9223372036854775808")))
        assertEquals("12345678901234567000", SocketIOJson.stringify(SocketIOJson.parse("12345678901234567890")))
        assertEquals(100L, SocketIOJson.parse("1e2").long)
        assertEquals(1L, SocketIOJson.parse("1.0").long)
    }

    @Test
    fun escapesStringsLikeJsonStringify() {
        val input = "\"\\/\b\u000c\n\r\t\u0001\u001f é🦧\u2028\ud800x\udc00"
        assertEquals(
            "\"\\\"\\\\/\\b\\f\\n\\r\\t\\u0001\\u001f é🦧\u2028\\ud800x\\udc00\"",
            SocketIOJson.stringify(SocketIOValue.Text(input)),
        )
        assertEquals(SocketIOValue.Text(input), SocketIOJson.parse(SocketIOJson.stringify(SocketIOValue.Text(input))))
    }

    @Test
    fun parsesEscapesAndWhitespaceLikeJsonParse() {
        assertEquals(SocketIOValue.Text("a/\u00e9\ud83e\udda7"), SocketIOJson.parse(" \t\n\r\"a\\/\\u00E9\\ud83e\\udda7\" "))
        assertEquals(socketIOArray(1, true, false, null, "x", emptyMap<String, Any>(), emptyList<Any>()), SocketIOJson.parse("[1,true,false,null,\"x\",{},[]]"))
    }

    @Test
    fun rejectsWhatJsonParseRejects() {
        for (bad in listOf(
            "", " ", "01", "+1", ".5", "1.", "1e", "-", "[1,]", "{\"a\":1,}", "{a:1}", "'a'", "NaN", "Infinity", "[1 2]",
            "\"\u0001\"", "\"\\x\"", "\"\\u12\"", "tru", "nul", "[", "{", "\"abc", "1 2", "{\"a\" 1}", "\u00a01",
        )) {
            assertThrows<SocketIOJsonException>("for <$bad>") { SocketIOJson.parse(bad) }
        }
    }

    @Test
    fun ordersObjectKeysLikeJavaScript() {
        val parsed = SocketIOJson.parse("{\"b\":1,\"2\":2,\"a\":3,\"1\":4,\"01\":5,\"4294967295\":6,\"4294967294\":7}")
        assertEquals(listOf("1", "2", "4294967294", "b", "a", "01", "4294967295"), parsed.obj!!.keys.toList())
        assertEquals("{\"1\":4,\"2\":2,\"4294967294\":7,\"b\":1,\"a\":3,\"01\":5,\"4294967295\":6}", SocketIOJson.stringify(parsed))
    }

    @Test
    fun duplicateKeysKeepTheFirstPositionAndTheLastValue() {
        val parsed = SocketIOJson.parse("{\"a\":1,\"b\":2,\"a\":3}")
        assertEquals("{\"a\":3,\"b\":2}", SocketIOJson.stringify(parsed))
    }

    @Test
    fun parsesAndPrintsDeepNestingWithoutRecursion() {
        val depth = 200_000
        val text = "[".repeat(depth) + "]".repeat(depth)
        val value = SocketIOJson.parse(text)
        assertEquals(text, SocketIOJson.stringify(value))
        assertEquals(value, SocketIOJson.parse(text))
        assertEquals(value.hashCode(), SocketIOJson.parse(text).hashCode())
        val objects = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
        assertEquals(objects, SocketIOJson.stringify(SocketIOJson.parse(objects)))
        assertThrows<SocketIOJsonException> { SocketIOJson.parse(text, maxDepth = 100) }
    }

    @Test
    fun appliesARevierBottomUpWithTheRootLast() {
        val calls = ArrayList<String>()
        val result =
            SocketIOJson.parse("{\"a\":[1,{\"b\":2}],\"c\":3}") { key, value ->
                calls += key
                when (key) {
                    "c" -> null
                    "b" -> SocketIOValue.Number.of(20)
                    else -> value
                }
            }
        assertEquals(listOf("0", "b", "1", "a", "c", ""), calls)
        assertEquals("{\"a\":[1,{\"b\":20}]}", SocketIOJson.stringify(result))
        assertEquals("[null,2]", SocketIOJson.stringify(SocketIOJson.parse("[1,2]") { key, v -> if (key == "0") null else v }))
    }

    @Test
    fun implementsJavaScriptNumberCoercion() {
        val cases =
            mapOf(
                "" to 0.0, " " to 0.0, "\n" to 0.0, "\u00a0" to 0.0, "1" to 1.0, " 12 " to 12.0, "1e3" to 1000.0, "0x1f" to 31.0,
                "0b101" to 5.0, "0o17" to 15.0, "+1" to 1.0, "-1.5" to -1.5, ".5" to 0.5, "5." to 5.0,
                "Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to Double.NEGATIVE_INFINITY,
            )
        for ((text, expected) in cases) assertEquals(expected, SocketIOJson.javaScriptNumber(text), "Number(\"$text\")")
        for (text in listOf("a", "1a", "0x", "-0x1", "1 2", "infinity", "1e", "--1", "+-1", ".", "e5")) {
            assertTrue(SocketIOJson.javaScriptNumber(text).isNaN(), "Number(\"$text\") is NaN")
        }
    }

    @Test
    fun equalityIsStructuralAndOrderInsensitiveForObjects() {
        assertEquals(socketIOObject("a" to 1, "b" to 2), socketIOObject("b" to 2, "a" to 1))
        assertEquals(socketIOObject("a" to 1, "b" to 2).hashCode(), socketIOObject("b" to 2, "a" to 1).hashCode())
        assertNotEquals(socketIOArray(1, 2), socketIOArray(2, 1))
        assertNotEquals(SocketIOValue.Number.of(0), SocketIOValue.Null)
        assertNotEquals(SocketIOValue.Null, SocketIOValue.Number.of(0))
        assertEquals(SocketIOValue.Binary(bytesOf(1)), SocketIOValue.Binary(bytesOf(1)))
    }
}
