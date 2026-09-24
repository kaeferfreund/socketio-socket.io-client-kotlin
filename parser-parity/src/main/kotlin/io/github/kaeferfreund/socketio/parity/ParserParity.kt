package io.github.kaeferfreund.socketio.parity

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.parser.SocketIODecoder
import io.github.kaeferfreund.socketio.parser.SocketIOEncoder
import io.github.kaeferfreund.socketio.parser.SocketIOJson
import io.github.kaeferfreund.socketio.parser.SocketIOPacket
import io.github.kaeferfreund.socketio.parser.SocketIOPacketType
import io.github.kaeferfreund.socketio.parser.SocketIOValue
import java.io.PrintStream

/**
 * Line protocol shared with `scripts/parser-parity/compare.cjs` (the harness of
 * the Swift fork, unchanged in format):
 *
 * - `{"header": "...", "binaries": [[...]]}` decodes one packet with the real
 *   library decoder and prints `{"status":"ok","type","id","nsp","data"}`,
 *   `{"status":"pending"}` or `{"status":"error"}`.
 * - `{"encode": {"type","nsp","id","data"}}` encodes one packet with the real
 *   library encoder and prints `{"status":"ok","header","binaries"}`.
 *
 * Binary data is exchanged as `{"__bytes": [...]}` markers.
 */
fun main() {
    val out = PrintStream(System.out, false, Charsets.UTF_8)
    System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        val result =
            try {
                val input = SocketIOJson.parse(line) as SocketIOValue.Object
                val spec = input["encode"]
                if (spec != null) encode(spec as SocketIOValue.Object) else decode(input)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                SocketIOValue.objectOf("status" to "error")
            }
        out.println(SocketIOJson.stringify(result))
    }
    out.flush()
}

private fun decode(input: SocketIOValue.Object): SocketIOValue {
    val decoder = SocketIODecoder()
    val header = input["header"]!!.string!!
    var packet = decoder.add(header)
    val binaries = input["binaries"]?.array.orEmpty()
    for (bytes in binaries) {
        if (packet != null || !decoder.isReconstructing) error("probe: unexpected attachment")
        packet = decoder.add(bytes.array!!.map { it.long!!.toByte() }.toByteArray())
    }
    if (packet == null) return SocketIOValue.objectOf("status" to "pending")
    val type =
        when (packet.type) {
            SocketIOPacketType.BINARY_EVENT -> SocketIOPacketType.EVENT
            SocketIOPacketType.BINARY_ACK -> SocketIOPacketType.ACK
            else -> packet.type
        }
    val data: SocketIOValue =
        when (type) {
            SocketIOPacketType.DISCONNECT -> SocketIOValue.Null
            else -> packet.data ?: SocketIOValue.Null
        }
    return SocketIOValue.objectOf(
        "status" to "ok",
        "type" to type.code,
        "id" to (packet.id ?: -1L),
        "nsp" to packet.nsp,
        "data" to normal(data),
    )
}

private fun encode(spec: SocketIOValue.Object): SocketIOValue {
    val type = SocketIOPacketType.fromCode(spec["type"]!!.int!!)!!
    val nsp = spec["nsp"]!!.string!!
    val id = spec["id"]?.long
    val data = spec["data"]?.let(::denormal)
    val frames = SocketIOEncoder().encode(SocketIOPacket(type, nsp, data, id))
    return SocketIOValue.objectOf(
        "status" to "ok",
        "header" to (frames[0] as EngineIOData.Text).value,
        "binaries" to frames.drop(1).map { frame -> (frame as EngineIOData.Binary).bytes.map { it.toInt() and 0xff } },
    )
}

/** Binary → `{"__bytes":[...]}`. Iterative, like every walk in the library. */
private fun normal(value: SocketIOValue): SocketIOValue = transform(value) { node ->
    (node as? SocketIOValue.Binary)?.let { SocketIOValue.objectOf("__bytes" to it.bytes.map { b -> b.toInt() and 0xff }) }
}

/** `{"__bytes":[...]}` → Binary. */
private fun denormal(value: SocketIOValue): SocketIOValue = transform(value) { node ->
    val bytes = (node as? SocketIOValue.Object)?.takeIf { it.size == 1 }?.get("__bytes")?.array
    bytes?.let { list -> SocketIOValue.Binary(list.map { it.long!!.toByte() }.toByteArray()) }
}

private fun transform(
    value: SocketIOValue,
    replace: (SocketIOValue) -> SocketIOValue?,
): SocketIOValue {
    replace(value)?.let { return it }
    return when (value) {
        is SocketIOValue.Array -> SocketIOValue.Array(value.items.map { transform(it, replace) })
        is SocketIOValue.Object -> SocketIOValue.Object(value.fields.mapValues { transform(it.value, replace) })
        else -> value
    }
}
