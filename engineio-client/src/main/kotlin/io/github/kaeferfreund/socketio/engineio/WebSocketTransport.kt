package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOParser

/**
 * WebSocket transport, a port of `BaseWS`/`WS` in `engine.io-client`, on
 * top of an [EngineWebSocketClient].
 *
 * Callbacks from the connection are ignored once this transport closed, so a
 * late frame of a replaced connection can never reach the engine.
 */
public class WebSocketTransport(
    options: TransportOptions,
) : EngineTransport(options) {
    override val name: String get() = NAME

    private val client: EngineWebSocketClient =
        requireNotNull(options.webSocketClient) {
            "The websocket transport needs an EngineWebSocketClient; add the socketio-okhttp module or configure one"
        }

    private var connection: EngineWebSocketConnection? = null
    private var generation = 0

    override fun doOpen() {
        val current = ++generation
        val request =
            EngineWebSocketRequest(
                url = uri(),
                headers = requestHeaders(),
                protocols = options.protocols,
                compressionThreshold = options.perMessageDeflateThreshold,
            )
        val jar = options.cookieJar
        connection =
            try {
                client.connect(
                    request,
                    object : EngineWebSocketListener {
                        private fun deliver(block: () -> Unit) {
                            executor.execute { if (current == generation) block() }
                        }

                        override fun onOpen(responseHeaders: List<Pair<String, String>>) =
                            deliver {
                                jar?.parseCookies(responseHeaders.filter { it.first.equals("set-cookie", true) }.map { it.second })
                                onOpen()
                            }

                        override fun onMessage(text: String) = deliver { onData(EngineIOData.Text(text)) }

                        override fun onMessage(bytes: ByteArray) = deliver { onData(EngineIOData.Binary.wrap(bytes)) }

                        override fun onClosed(
                            code: Int,
                            reason: String,
                        ) = deliver { onClose(CloseDetails("websocket connection closed", code, reason)) }

                        override fun onFailure(
                            error: Throwable,
                            httpStatus: Int?,
                        ) = deliver {
                            // A browser reports an error event followed by a close event; the
                            // engine reacts to the error first (transport error).
                            onError("websocket error", statusCode = httpStatus, cause = error)
                            if (readyState != TransportState.CLOSED) {
                                onClose(CloseDetails("websocket connection closed", code = 1006, reason = error.message.orEmpty()))
                            }
                        }
                    },
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                onError("websocket error", cause = e)
                null
            }
    }

    override fun write(packets: List<EngineIOPacket>) {
        writable = false
        for ((index, packet) in packets.withIndex()) {
            val data = EngineIOParser.encodePacket(packet, supportsBinary)
            doWrite(packet, data)
            if (index == packets.size - 1) {
                // nextTick: the drain comes after the caller's synchronous code.
                val current = generation
                executor.post {
                    if (current != generation) return@post
                    writable = true
                    events.emit(TransportEvent.Drain)
                }
            }
        }
    }

    private fun doWrite(
        packet: EngineIOPacket,
        data: EngineIOData,
    ) {
        val ws = connection ?: return
        var compress = packet.options.compress
        val threshold = options.perMessageDeflateThreshold
        if (threshold != null) {
            val length =
                when (data) {
                    is EngineIOData.Text -> EngineUri.utf8Length(data.value)
                    is EngineIOData.Binary -> data.size.toLong()
                }
            if (length < threshold) compress = false
        }
        val accepted =
            when (data) {
                is EngineIOData.Text -> ws.send(data.value, compress)
                is EngineIOData.Binary -> ws.send(data.bytes, compress)
            }
        // JavaScript swallows a send on a closing socket ("websocket closed before onclose event");
        // the close callback that follows reports the failure.
        if (!accepted) options.logger.debug("websocket") { "send refused; waiting for the close event" }
    }

    override fun doClose() {
        generation++
        connection?.close(1000, null)
        connection = null
    }

    @InternalSocketIOApi
    override fun uri(): String {
        val schema = if (options.secure) "wss" else "ws"
        if (options.timestampRequests == true) query[options.timestampParam] = EngineUri.randomString()
        if (!supportsBinary) query["b64"] = "1"
        return createUri(schema, query)
    }

    public companion object {
        public const val NAME: String = "websocket"

        /** Factory for [EngineOptions.transportFactories]. */
        public val FACTORY: Factory = Factory { WebSocketTransport(it) }
    }
}
