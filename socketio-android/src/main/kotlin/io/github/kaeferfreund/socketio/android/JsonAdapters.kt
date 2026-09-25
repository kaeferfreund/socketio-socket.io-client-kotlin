package io.github.kaeferfreund.socketio.android

import android.util.JsonReader
import android.util.JsonToken
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader

/**
 * Converts to `org.json` types: objects become [JSONObject], arrays
 * [JSONArray], `null` becomes [JSONObject.NULL]. Binary data has no JSON
 * form and is written as a `ByteArray` value. Nesting is handled
 * iteratively, so a deeply nested payload cannot overflow the stack.
 */
public fun SocketIOValue.toJson(): Any {
    jsonLeaf(this)?.let { return it }

    class Frame(
        val source: SocketIOValue,
        val target: Any,
    ) {
        val entries: List<Pair<String?, SocketIOValue>> =
            if (source is SocketIOValue.Object) source.fields.entries.map { it.key to it.value } else (source as SocketIOValue.Array).items.map { null to it }
        var index = 0

        fun add(
            key: String?,
            value: Any,
        ) {
            if (target is JSONObject) target.put(key!!, value) else (target as JSONArray).put(value)
        }
    }

    fun container(value: SocketIOValue): Any = if (value is SocketIOValue.Object) JSONObject() else JSONArray()
    val root = Frame(this, container(this))
    val stack = ArrayDeque<Frame>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val frame = stack.last()
        if (frame.index == frame.entries.size) {
            stack.removeLast()
            continue
        }
        val (key, child) = frame.entries[frame.index++]
        val leaf = jsonLeaf(child)
        if (leaf != null) {
            frame.add(key, leaf)
        } else {
            val nested = Frame(child, container(child))
            frame.add(key, nested.target)
            stack.addLast(nested)
        }
    }
    return root.target
}

private fun jsonLeaf(value: SocketIOValue): Any? =
    when (value) {
        is SocketIOValue.Null -> JSONObject.NULL
        is SocketIOValue.Bool -> value.value
        is SocketIOValue.Number -> value.value
        is SocketIOValue.Text -> value.value
        is SocketIOValue.Binary -> value.bytes
        is SocketIOValue.Array, is SocketIOValue.Object -> null
    }

/** This object as a [JSONObject]. */
public fun SocketIOValue.Object.toJSONObject(): JSONObject = toJson() as JSONObject

/** This array as a [JSONArray]. */
public fun SocketIOValue.Array.toJSONArray(): JSONArray = toJson() as JSONArray

/** A [JSONObject] as a Socket.IO value, for apps migrating from `socket.io-client-java`. */
public fun JSONObject.toSocketIOValue(): SocketIOValue.Object = fromJson(this) as SocketIOValue.Object

/** A [JSONArray] as a Socket.IO value. */
public fun JSONArray.toSocketIOValue(): SocketIOValue.Array = fromJson(this) as SocketIOValue.Array

/**
 * Converts `org.json` values (`JSONObject`, `JSONArray`, `JSONObject.NULL`,
 * primitives, `ByteArray`): the iterative conversion of [SocketIOValue.of].
 */
public fun fromJson(value: Any?): SocketIOValue = SocketIOValue.of(value)

/**
 * Streams a JSON document from [reader] with `android.util.JsonReader`,
 * without first materializing it as a string — for large payloads.
 * Nesting is handled iteratively. The document must hold exactly one value;
 * anything after it fails like other malformed input.
 */
public fun readSocketIOValue(reader: Reader): SocketIOValue {
    JsonReader(reader).use { json ->
        json.isLenient = false
        val value = readValue(json)
        // In strict mode, peek() throws for data after the value and reports END_DOCUMENT otherwise.
        check(json.peek() == JsonToken.END_DOCUMENT) { "unexpected data after the JSON value" }
        return value
    }
}

private fun readValue(json: JsonReader): SocketIOValue {
    class Frame(
        val fields: LinkedHashMap<String, SocketIOValue>?,
        val items: ArrayList<SocketIOValue>?,
    ) {
        var key: String? = null
    }
    val stack = ArrayDeque<Frame>()
    var result: SocketIOValue? = null
    while (true) {
        val frame = stack.lastOrNull()
        if (frame?.fields != null && json.peek() == JsonToken.NAME) frame.key = json.nextName()
        val value: SocketIOValue? =
            when (json.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    json.beginObject()
                    stack.addLast(Frame(LinkedHashMap(), null))
                    null
                }

                JsonToken.BEGIN_ARRAY -> {
                    json.beginArray()
                    stack.addLast(Frame(null, ArrayList()))
                    null
                }

                JsonToken.END_OBJECT -> {
                    json.endObject()
                    SocketIOValue.Object(stack.removeLast().fields!!)
                }

                JsonToken.END_ARRAY -> {
                    json.endArray()
                    SocketIOValue.Array(stack.removeLast().items!!)
                }

                JsonToken.STRING -> SocketIOValue.Text(json.nextString())

                JsonToken.NUMBER -> {
                    val text = json.nextString()
                    text.toLongOrNull()?.let { SocketIOValue.Number.of(it) } ?: SocketIOValue.Number.of(text.toDouble())
                }

                JsonToken.BOOLEAN -> SocketIOValue.Bool.of(json.nextBoolean())

                JsonToken.NULL -> {
                    json.nextNull()
                    SocketIOValue.Null
                }

                else -> throw IllegalArgumentException("unexpected token ${json.peek()}")
            }
        if (value == null) continue
        val parent = stack.lastOrNull()
        when {
            parent == null -> {
                result = value
                break
            }

            parent.fields != null -> parent.fields[parent.key!!] = value

            else -> parent.items!!.add(value)
        }
    }
    return result
}
