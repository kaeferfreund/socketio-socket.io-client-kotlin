package io.github.kaeferfreund.socketio.parser

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Date

class SocketIOValueTest {
    private enum class Color { RED }

    // Documented conversions: every primitive array becomes a JSON array (like Array.from on a
    // typed array), a CharArray an array of one-character strings, and map keys use toString().
    @Test
    fun aNullMapKeyBecomesTheStringNullLikeToString() {
        assertEquals(socketIOObject("null" to 1), SocketIOValue.of(mapOf(null to 1)))
        assertEquals(socketIOObject("x" to socketIOObject("null" to true)), SocketIOValue.of(mapOf("x" to mapOf(null to true))))
    }

    @Test
    fun convertsPrimitiveArraysAndNonStringKeys() {
        assertEquals(SocketIOValue.of(listOf(1, 2)), SocketIOValue.of(intArrayOf(1, 2)))
        assertEquals(SocketIOValue.of(listOf(3L, Long.MAX_VALUE)), SocketIOValue.of(longArrayOf(3, Long.MAX_VALUE)))
        assertEquals(SocketIOValue.of(listOf(4, -5)), SocketIOValue.of(shortArrayOf(4, -5)))
        assertEquals(SocketIOValue.of(listOf(0.5, 1.25)), SocketIOValue.of(doubleArrayOf(0.5, 1.25)))
        // A Float is sent as the decimal it was written as (0.1f -> 0.1), not as its widened
        // binary value 0.10000000149011612, in arrays as for single values.
        assertEquals("[0.1,2.5]", SocketIOJson.stringify(SocketIOValue.of(floatArrayOf(0.1f, 2.5f))))
        assertEquals(SocketIOValue.of(listOf(true, false)), SocketIOValue.of(booleanArrayOf(true, false)))
        assertEquals(SocketIOValue.of(listOf("h", "i")), SocketIOValue.of(charArrayOf('h', 'i')))
        // A ByteArray is binary, not an array of numbers.
        assertTrue(SocketIOValue.of(byteArrayOf(1)) is SocketIOValue.Binary)
        assertEquals("{\"1\":\"a\",\"true\":\"b\"}", SocketIOJson.stringify(SocketIOValue.of(mapOf(1 to "a", true to "b"))))
    }

    @Test
    fun convertsKotlinAndJavaValues() {
        val value =
            SocketIOValue.of(
                mapOf(
                    "null" to null,
                    "bool" to true,
                    "int" to 1,
                    "long" to 2L,
                    "short" to 3.toShort(),
                    "byte" to 4.toByte(),
                    "double" to 1.5,
                    "float" to 0.1f,
                    "big" to java.math.BigInteger("123"),
                    "decimal" to java.math.BigDecimal("1.25"),
                    "text" to "x",
                    "char" to 'c',
                    "enum" to Color.RED,
                    "bytes" to bytesOf(1),
                    "list" to listOf(1, "a"),
                    "array" to arrayOf(1, 2),
                    "ints" to intArrayOf(3),
                    "sequence" to sequenceOf(4),
                    "set" to setOf("s"),
                ),
            )
        assertEquals(
            "{\"null\":null,\"bool\":true,\"int\":1,\"long\":2,\"short\":3,\"byte\":4,\"double\":1.5,\"float\":0.1,\"big\":123," +
                "\"decimal\":1.25,\"text\":\"x\",\"char\":\"c\",\"enum\":\"RED\",\"bytes\":\"<binary 1 bytes>\",\"list\":[1,\"a\"]," +
                "\"array\":[1,2],\"ints\":[3],\"sequence\":[4],\"set\":[\"s\"]}",
            value.toString(),
        )
    }

    @Test
    fun convertsDatesLikeJavaScriptToIsoString() {
        val instant = Instant.parse("2026-09-24T12:34:56.789Z")
        assertEquals(SocketIOValue.Text("2026-09-24T12:34:56.789Z"), SocketIOValue.of(instant))
        assertEquals(SocketIOValue.Text("2026-09-24T12:34:56.789Z"), SocketIOValue.of(Date.from(instant)))
        assertEquals(SocketIOValue.Text("1970-01-01T00:00:00.000Z"), SocketIOValue.of(Instant.EPOCH))
    }

    @Test
    fun rejectsUnsupportedTypes() {
        val error = assertThrows<IllegalArgumentException> { SocketIOValue.of(Any()) }
        assertTrue(error.message!!.contains("java.lang.Object"))
    }

    @Test
    fun sharedButAcyclicReferencesAreFine() {
        val shared = listOf(1)
        assertEquals("[[1],[1]]", SocketIOValue.of(listOf(shared, shared)).toString())
    }

    @Test
    fun convertsDeepInputWithoutRecursion() {
        var nested: Any = emptyList<Any>()
        repeat(100_000) { nested = listOf(nested) }
        val value = SocketIOValue.of(nested)
        assertTrue(value.toString().startsWith("[[[["))
    }

    @Test
    fun exposesTypedAccessors() {
        val value = SocketIOValue.of(mapOf("n" to 5, "d" to 2.5, "s" to "t", "b" to false, "x" to bytesOf(9), "a" to listOf(1)))
        val obj = value.obj!!
        assertEquals(5L, obj["n"]!!.long)
        assertEquals(5, obj["n"]!!.int)
        assertEquals(2.5, obj["d"]!!.double)
        assertNull(obj["d"]!!.long)
        assertEquals("t", obj["s"]!!.string)
        assertEquals(false, obj["b"]!!.boolean)
        assertArrayEquals(bytesOf(9), obj["x"]!!.bytes)
        assertEquals(listOf(SocketIOValue.Number.of(1)), obj["a"]!!.array)
        assertTrue(value.containsBinary)
        assertFalse(SocketIOValue.of(listOf(1)).containsBinary)
        assertTrue(SocketIOValue.Null.isNull)
        assertNull(SocketIOValue.of(Long.MAX_VALUE).int)
    }

    @Test
    fun convertsBackToKotlin() {
        val value = SocketIOValue.of(mapOf("a" to listOf(1, 2.5, null, "x", mapOf("b" to true))))
        assertEquals(mapOf("a" to listOf(1L, 2.5, null, "x", mapOf("b" to true))), value.toKotlin())
    }

    @Test
    fun valuesAreImmutable() {
        val source = mutableListOf<SocketIOValue>(SocketIOValue.Number.of(1))
        val array = SocketIOValue.Array(source)
        source.add(SocketIOValue.Null)
        assertEquals(1, array.size)
        val bytes = bytesOf(1, 2)
        val binary = SocketIOValue.Binary(bytes)
        bytes[0] = 7
        assertArrayEquals(bytesOf(1, 2), binary.bytes)
    }
}

class OrgJsonConversionTest {
    @org.junit.jupiter.api.Test
    fun convertsOrgJsonPayloadsLikeTheJavaClientAccepts() {
        val json = org.json.JSONObject()
        json.put("text", "x")
        json.put("n", 2)
        json.put("nothing", org.json.JSONObject.NULL)
        json.put("list", org.json.JSONArray().put(1).put(true))
        json.put("bytes", byteArrayOf(1, 2))
        val value = SocketIOValue.of(json)
        val expected = SocketIOValue.of(mapOf("text" to "x", "n" to 2, "nothing" to null, "list" to listOf(1, true), "bytes" to byteArrayOf(1, 2)))
        org.junit.jupiter.api.Assertions.assertEquals(expected, value)
        org.junit.jupiter.api.Assertions.assertTrue(value.containsBinary)
        org.junit.jupiter.api.Assertions.assertEquals(SocketIOValue.Null, SocketIOValue.of(org.json.JSONObject.NULL))
        org.junit.jupiter.api.Assertions.assertEquals(SocketIOValue.arrayOf("a"), SocketIOValue.of(org.json.JSONArray().put("a")))
    }

    // socket.io-client-java#743: a primitive array put into a JSONObject reached the server as
    // "[I@50bf616", because org.json stringified the array object. It must arrive as a JSON array.
    @org.junit.jupiter.api.Test
    fun primitiveArraysInsideOrgJsonBecomeJsonArrays() {
        val json = org.json.JSONObject().put("sellCIndices", intArrayOf(0, 2)).put("next", "PROCESS")
        val value = SocketIOValue.of(json)
        org.junit.jupiter.api.Assertions.assertEquals(SocketIOValue.of(mapOf("sellCIndices" to listOf(0, 2), "next" to "PROCESS")), value)
        // Key order is whatever the org.json implementation iterates (HashMap on the JVM).
        org.junit.jupiter.api.Assertions.assertTrue(SocketIOJson.stringify(value).contains("\"sellCIndices\":[0,2]"))
    }
}
