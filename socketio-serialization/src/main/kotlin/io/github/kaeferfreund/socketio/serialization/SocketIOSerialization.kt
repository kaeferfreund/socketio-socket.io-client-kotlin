package io.github.kaeferfreund.socketio.serialization

import io.github.kaeferfreund.socketio.AckCallback
import io.github.kaeferfreund.socketio.IncomingEvent
import io.github.kaeferfreund.socketio.Socket
import io.github.kaeferfreund.socketio.SocketEmitter
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

    /** Converts a JSON tree to a Socket.IO value, without recursion. */
    public fun fromJsonElement(element: JsonElement): SocketIOValue =
        convertTree<JsonElement, SocketIOValue>(
            element,
            leaf = { node ->
                when (node) {
                    is JsonNull -> SocketIOValue.Null
                    is JsonPrimitive -> primitive(node)
                    else -> null
                }
            },
            children = { node -> if (node is JsonObject) node.entries.map { it.key to it.value } else (node as JsonArray).map { null to it } },
            build = { node, keys, values ->
                if (node is JsonObject) SocketIOValue.Object(keys.zip(values).toMap(LinkedHashMap())) else SocketIOValue.Array(values)
            },
        )

    private fun primitive(element: JsonPrimitive): SocketIOValue =
        when {
            element.isString -> SocketIOValue.Text(element.content)
            element.content == "true" -> SocketIOValue.TRUE
            element.content == "false" -> SocketIOValue.FALSE
            else -> SocketIOJson.parse(element.content)
        }

    /**
     * Converts a Socket.IO value to a JSON tree, without recursion, so a deeply
     * nested payload received from a server cannot overflow the stack; binary
     * data is rejected.
     */
    public fun toJsonElement(value: SocketIOValue): JsonElement =
        convertTree<SocketIOValue, JsonElement>(
            value,
            leaf = { node ->
                when (node) {
                    is SocketIOValue.Null -> JsonNull
                    is SocketIOValue.Bool -> JsonPrimitive(node.value)
                    is SocketIOValue.Number -> JsonPrimitive(node.value)
                    is SocketIOValue.Text -> JsonPrimitive(node.value)
                    is SocketIOValue.Binary -> throw IllegalArgumentException("binary data cannot be decoded with kotlinx.serialization")
                    is SocketIOValue.Array, is SocketIOValue.Object -> null
                }
            },
            children = { node ->
                if (node is SocketIOValue.Object) node.fields.entries.map { it.key to it.value } else (node as SocketIOValue.Array).items.map { null to it }
            },
            build = { node, keys, values ->
                if (node is SocketIOValue.Object) JsonObject(keys.zip(values).toMap(LinkedHashMap())) else JsonArray(values)
            },
        )

    /**
     * Rebuilds a tree bottom-up on an explicit stack. [leaf] converts a scalar or
     * returns `null` for a container, whose [children] (key or `null`, value) are
     * converted in order and handed to [build].
     */
    private fun <S : Any, T : Any> convertTree(
        root: S,
        leaf: (S) -> T?,
        children: (S) -> List<Pair<String?, S>>,
        build: (S, List<String>, List<T>) -> T,
    ): T {
        leaf(root)?.let { return it }

        class Frame(
            val node: S,
        ) {
            val entries = children(node)
            val built = ArrayList<T>(entries.size)
        }
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root))
        var result: T? = null
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.built.size < frame.entries.size) {
                val child = frame.entries[frame.built.size].second
                val converted = leaf(child)
                if (converted != null) frame.built.add(converted) else stack.addLast(Frame(child))
                continue
            }
            stack.removeLast()
            val built = build(frame.node, frame.entries.mapNotNull { it.first }, frame.built)
            val parent = stack.lastOrNull()
            if (parent == null) result = built else parent.built.add(built)
        }
        return result!!
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
public inline fun <reified T> T.encodeToSocketIOValue(json: Json = SocketIOSerialization.DefaultJson): SocketIOValue =
    SocketIOSerialization.encode(serializer<T>(), this, json)

/** Decodes this value into a `@Serializable` type. */
public inline fun <reified T> SocketIOValue.decodeAs(json: Json = SocketIOSerialization.DefaultJson): T =
    SocketIOSerialization.decode(serializer<T>(), this, json)

/** Emits [event] with [value] encoded by `kotlinx.serialization`. */
public inline fun <reified T> Socket.emitSerializable(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): Socket = emit(event, value.encodeToSocketIOValue(json))

/** Emits [event] with [value] encoded by `kotlinx.serialization`, with this emitter's flags. */
public inline fun <reified T> SocketEmitter.emitSerializable(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): SocketEmitter = emit(event, value.encodeToSocketIOValue(json))

/** Like [Socket.emitSerializableWithAck], with this emitter's flags (for example a timeout). */
public suspend inline fun <reified T, reified R> SocketEmitter.emitSerializableWithAck(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): R = emitWithAck(event, value.encodeToSocketIOValue(json)).first().decodeAs(json)

/** Emits [event] with [value] and decodes the first acknowledgement argument as [R]. */
public suspend inline fun <reified T, reified R> Socket.emitSerializableWithAck(
    event: String,
    value: T,
    json: Json = SocketIOSerialization.DefaultJson,
): R = emitWithAck(event, value.encodeToSocketIOValue(json)).first().decodeAs(json)

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
