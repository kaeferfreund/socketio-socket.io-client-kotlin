package io.github.kaeferfreund.socketio.serialization

import io.github.kaeferfreund.socketio.AckCallback
import io.github.kaeferfreund.socketio.IncomingEvent
import io.github.kaeferfreund.socketio.Socket
import io.github.kaeferfreund.socketio.Subscription
import io.github.kaeferfreund.socketio.parser.SocketIOJson
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * `kotlinx.serialization` support: `@Serializable` classes in and out of
 * Socket.IO values, through the JSON tree of the given [Json] instance
 * (its naming strategy, defaults and `ignoreUnknownKeys` apply).
 *
 * Binary attachments have no JSON form: keep `ByteArray` payloads as
 * separate emit arguments (or as [SocketIOValue.Binary]) rather than inside
 * serializable classes.
 */
public object SocketIOSerialization {
    /** The instance used when none is given: lenient about unknown keys, as servers evolve. */
    public val DefaultJson: Json = Json { ignoreUnknownKeys = true }

    /** Converts a JSON tree to a Socket.IO value. */
    public fun fromJsonElement(element: JsonElement): SocketIOValue =
        when (element) {
            is JsonNull -> SocketIOValue.Null
            is JsonPrimitive ->
                when {
                    element.isString -> SocketIOValue.Text(element.content)
                    element.content == "true" -> SocketIOValue.TRUE
                    element.content == "false" -> SocketIOValue.FALSE
                    else -> SocketIOJson.parse(element.content)
                }
            is JsonArray -> SocketIOValue.Array(element.map(::fromJsonElement))
            is JsonObject -> SocketIOValue.Object(element.mapValues { fromJsonElement(it.value) })
        }

    /** Converts a Socket.IO value to a JSON tree; binary data is rejected. */
    public fun toJsonElement(value: SocketIOValue): JsonElement =
        when (value) {
            is SocketIOValue.Null -> JsonNull
            is SocketIOValue.Bool -> JsonPrimitive(value.value)
            is SocketIOValue.Number -> JsonPrimitive(value.value)
            is SocketIOValue.Text -> JsonPrimitive(value.value)
            is SocketIOValue.Binary -> throw IllegalArgumentException("binary data cannot be decoded with kotlinx.serialization")
            is SocketIOValue.Array -> JsonArray(value.items.map(::toJsonElement))
            is SocketIOValue.Object -> JsonObject(value.fields.mapValues { toJsonElement(it.value) })
        }

    /** Encodes [value] with [serializer]. */
    public fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = DefaultJson,
    ): SocketIOValue = fromJsonElement(json.encodeToJsonElement(serializer, value))

    /** Decodes [value] with [deserializer]. */
    public fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        value: SocketIOValue,
        json: Json = DefaultJson,
    ): T = json.decodeFromJsonElement(deserializer, toJsonElement(value))
}

/** Encodes a `@Serializable` value. */
public inline fun <reified T> T.toSocketIOValue(json: Json = SocketIOSerialization.DefaultJson): SocketIOValue =
    SocketIOSerialization.encode(serializer<T>(), this, json)

/** Decodes this value into a `@Serializable` type. */
public inline fun <reified T> SocketIOValue.decodeAs(json: Json = SocketIOSerialization.DefaultJson): T =
    SocketIOSerialization.decode(serializer<T>(), this, json)

/** Emits [event] with [value] encoded by `kotlinx.serialization`. */
public inline fun <reified T> Socket.emitSerializable(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): Socket = emit(event, value.toSocketIOValue(json))

/** Emits [event] with [value] and decodes the first acknowledgement argument as [R]. */
public suspend inline fun <reified T, reified R> Socket.emitSerializableWithAck(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): R = emitWithAck(event, value.toSocketIOValue(json)).first().decodeAs(json)

/**
 * Calls [listener] with the first argument of every [event], decoded as [T].
 * A payload that does not decode is reported to [onError] (by default
 * rethrown, which the manager's listener error handler receives).
 */
public inline fun <reified T> Socket.onSerializable(
    event: String,
    json: Json = SocketIOSerialization.DefaultJson,
    noinline onError: (IncomingEvent, Exception) -> Unit = { _, error -> throw error },
    crossinline listener: (T, IncomingEvent) -> Unit,
): Subscription =
    on(event) { incoming ->
        val decoded =
            try {
                (incoming.args.firstOrNull() ?: SocketIOValue.Null).decodeAs<T>(json)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                onError(incoming, e)
                return@on
            }
        listener(decoded, incoming)
    }

/** An [AckCallback] that decodes the first acknowledgement argument as [T]. */
public inline fun <reified T> serializableAck(
    json: Json = SocketIOSerialization.DefaultJson,
    crossinline callback: (Result<T>) -> Unit,
): AckCallback = AckCallback { result -> callback(result.mapCatching { args -> args.first().decodeAs<T>(json) }) }
