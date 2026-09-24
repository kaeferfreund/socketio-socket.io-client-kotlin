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
 * form and is written as a `ByteArray` value.
 */
public fun SocketIOValue.toJson(): Any =
    when (this) {
        is SocketIOValue.Null -> JSONObject.NULL
        is SocketIOValue.Bool -> value
        is SocketIOValue.Number -> value
        is SocketIOValue.Text -> value
        is SocketIOValue.Binary -> bytes
        is SocketIOValue.Array -> JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }
        is SocketIOValue.Object -> JSONObject().also { obj -> fields.forEach { (key, value) -> obj.put(key, value.toJson()) } }
    }

/** This object as a [JSONObject]. */
public fun SocketIOValue.Object.toJSONObject(): JSONObject = toJson() as JSONObject

/** This array as a [JSONArray]. */
public fun SocketIOValue.Array.toJSONArray(): JSONArray = toJson() as JSONArray

/** A [JSONObject] as a Socket.IO value, for apps migrating from `socket.io-client-java`. */
public fun JSONObject.toSocketIOValue(): SocketIOValue.Object = fromJson(this) as SocketIOValue.Object

/** A [JSONArray] as a Socket.IO value. */
public fun JSONArray.toSocketIOValue(): SocketIOValue.Array = fromJson(this) as SocketIOValue.Array

/** Converts `org.json` values (`JSONObject`, `JSONArray`, `JSONObject.NULL`, primitives, `ByteArray`). */
public fun fromJson(value: Any?): SocketIOValue =
    when (value) {
        null, JSONObject.NULL -> SocketIOValue.Null
        is JSONObject -> {
            val map = LinkedHashMap<String, SocketIOValue>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = fromJson(value.opt(key))
            }
            SocketIOValue.Object(map)
        }
        is JSONArray -> SocketIOValue.Array(List(value.length()) { fromJson(value.opt(it)) })
        else -> SocketIOValue.of(value)
    }

/**
 * Streams a JSON document from [reader] with `android.util.JsonReader`,
 * without first materializing it as a string — for large payloads.
 * Nesting is handled iteratively.
 */
public fun readSocketIOValue(reader: Reader): SocketIOValue {
    JsonReader(reader).use { json ->
        json.isLenient = false
        return readValue(json)
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
