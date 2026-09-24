package io.github.kaeferfreund.socketio.serialization

import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SocketIOSerializationTest {
    @Serializable
    data class Stamp(
        val id: Long,
        val label: String,
        @SerialName("at") val timestamp: Double,
        val tags: List<String> = emptyList(),
        val note: String? = null,
    )

    @Test
    fun roundTripsSerializableClasses() {
        val stamp = Stamp(42, "in", 1.5, listOf("a", "b"))
        val value = stamp.toSocketIOValue()
        assertEquals(SocketIOValue.of(mapOf("id" to 42, "label" to "in", "at" to 1.5, "tags" to listOf("a", "b"))), value)
        assertEquals(stamp, value.decodeAs<Stamp>())
    }

    @Test
    fun ignoresUnknownKeysByDefault() {
        val value = SocketIOValue.of(mapOf("id" to 1, "label" to "x", "at" to 2, "extra" to true))
        assertEquals(Stamp(1, "x", 2.0), value.decodeAs<Stamp>())
    }

    @Test
    fun convertsJsonTreesBothWays() {
        val value = SocketIOValue.of(mapOf("n" to 9007199254740993L, "d" to 0.1, "s" to "t", "b" to false, "z" to null, "a" to listOf(1)))
        assertEquals(value, SocketIOSerialization.fromJsonElement(SocketIOSerialization.toJsonElement(value)))
    }

    @Test
    fun rejectsBinaryAndMismatchedPayloads() {
        assertThrows<IllegalArgumentException> { SocketIOValue.Binary(byteArrayOf(1)).decodeAs<Stamp>() }
        assertThrows<SerializationException> { SocketIOValue.of(mapOf("id" to "x")).decodeAs<Stamp>() }
    }
}
