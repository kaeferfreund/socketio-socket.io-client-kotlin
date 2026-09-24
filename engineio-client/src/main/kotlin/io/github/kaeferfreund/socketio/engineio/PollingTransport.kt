package io.github.kaeferfreund.socketio.engineio

import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacket
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOPacketType
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOParser

/**
 * HTTP long-polling, a port of `Polling` and `BaseXHR`/`Request` in
 * `engine.io-client`, on top of an [EngineHttpClient].
 *
 * One GET is outstanding at a time; POSTs never overlap because the engine
 * only writes while the transport is [writable]. Differences from JavaScript,
 * all toward robustness: a response that arrives after the transport closed
 * is ignored, the outstanding GET is cancelled on close (the close POST is
 * still sent), and a response body may be bounded by
 * [TransportOptions.maxPollingResponseBytes].
 */
public class PollingTransport(
    options: TransportOptions,
) : EngineTransport(options) {
    override val name: String get() = NAME

    private val http: EngineHttpClient =
        requireNotNull(options.httpClient) {
            "The polling transport needs an EngineHttpClient; add the socketio-okhttp module or configure one"
        }

    private var polling = false
    private var pollRequest: Cancellable? = null

    /** Generation of the current open cycle; stale callbacks compare against it. */
    private var closedGeneration = 0

    override fun doOpen() {
        poll()
    }

    override fun pause(onPause: () -> Unit) {
        readyState = TransportState.PAUSING
        val pause = {
            readyState = TransportState.PAUSED
            onPause()
        }
        if (polling || !writable) {
            var total = 0
            if (polling) {
                total++
                events.once<TransportEvent.PollComplete, TransportEvent> {
                    if (--total == 0) pause()
                }
            }
            if (!writable) {
                total++
                events.once<TransportEvent.Drain, TransportEvent> {
                    if (--total == 0) pause()
                }
            }
        } else {
            pause()
        }
    }

    private fun poll() {
        polling = true
        doPoll()
        events.emit(TransportEvent.Poll)
    }

    /** Handles one polling response body (`onData` in JavaScript). */
    private fun onPayload(data: String) {
        for (packet in EngineIOParser.decodePayload(data)) {
            if (readyState == TransportState.OPENING && packet.type == EngineIOPacketType.OPEN) onOpen()
            if (packet.type == EngineIOPacketType.CLOSE) {
                onClose(CloseDetails("transport closed by the server"))
                // JavaScript's forEach ignores the callback's `return false`
                // and hands later packets to listeners that no longer exist.
                continue
            }
            onPacket(packet)
        }
        if (readyState != TransportState.CLOSED) {
            polling = false
            events.emit(TransportEvent.PollComplete)
            if (readyState == TransportState.OPEN) poll()
        }
    }

    override fun doClose() {
        val close = { write(listOf(EngineIOPacket(EngineIOPacketType.CLOSE))) }
        if (readyState == TransportState.OPEN) {
            close()
        } else {
            // The handshake may still be pending: send the close packet once it opens.
            events.once<TransportEvent.Open, TransportEvent> { close() }
        }
    }

    override fun onClose(details: CloseDetails?) {
        closedGeneration++
        pollRequest?.cancel()
        pollRequest = null
        super.onClose(details)
    }

    override fun write(packets: List<EngineIOPacket>) {
        writable = false
        val body = EngineIOParser.encodePayload(packets)
        doWrite(body) {
            writable = true
            events.emit(TransportEvent.Drain)
        }
    }

    @InternalSocketIOApi
    override fun uri(): String {
        val schema = if (options.secure) "https" else "http"
        if (options.timestampRequests != false) query[options.timestampParam] = EngineUri.randomString()
        if (!supportsBinary && !query.containsKey("sid")) query["b64"] = "1"
        return createUri(schema, query)
    }

    private fun doPoll() {
        val generation = closedGeneration
        pollRequest =
            request("GET", null) { result ->
                if (generation != closedGeneration) return@request
                pollRequest = null
                result.fold(
                    onSuccess = { onPayload(it) },
                    onFailure = { reportFailure("xhr poll error", it) },
                )
            }
    }

    private fun doWrite(
        body: String,
        onSuccess: () -> Unit,
    ) {
        // A POST outlives a close on purpose: it may carry the close packet.
        request("POST", body) { result ->
            result.fold(
                onSuccess = { onSuccess() },
                onFailure = { reportFailure("xhr post error", it) },
            )
        }
    }

    private fun reportFailure(
        reason: String,
        error: Throwable,
    ) {
        if (error is HttpStatusException) {
            onError(reason, statusCode = error.status, responseBody = error.body)
        } else {
            onError(reason, cause = error)
        }
    }

    private class HttpStatusException(
        val status: Int,
        val body: String,
    ) : RuntimeException("HTTP $status")

    private fun request(
        method: String,
        body: String?,
        onResult: (Result<String>) -> Unit,
    ): Cancellable {
        val headers = requestHeaders()
        if (method == "POST") headers.add("Content-type" to "text/plain;charset=UTF-8")
        headers.add("Accept" to "*/*")
        val request =
            EngineHttpRequest(
                method = method,
                url = uri(),
                headers = headers,
                body = body,
                timeout = options.requestTimeout,
                maxResponseBytes = options.maxPollingResponseBytes,
            )
        val jar = options.cookieJar
        return http.execute(
            request,
            object : EngineHttpCallback {
                override fun onResponse(response: EngineHttpResponse) {
                    executor.execute {
                        jar?.parseCookies(response.headerValues("set-cookie"))
                        // 1223 is IE's mangled 204; kept for fidelity with the JavaScript check.
                        if (response.status == 200 || response.status == 1223) {
                            onResult(Result.success(response.body))
                        } else {
                            onResult(Result.failure(HttpStatusException(response.status, response.body)))
                        }
                    }
                }

                override fun onFailure(error: Throwable) {
                    executor.execute { onResult(Result.failure(error)) }
                }
            },
        )
    }

    public companion object {
        public const val NAME: String = "polling"

        /** Factory for [EngineOptions.transportFactories]. */
        public val FACTORY: Factory = Factory { PollingTransport(it) }
    }
}

