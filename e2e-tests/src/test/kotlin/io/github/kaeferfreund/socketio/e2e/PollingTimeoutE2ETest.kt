package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.engineio.EngineEvent
import io.github.kaeferfreund.socketio.engineio.EngineOptions
import io.github.kaeferfreund.socketio.engineio.TransportException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * `requestTimeout` against the Swift fork's `polling-request-timeout.mjs`: a
 * stalled handshake, a handshake that trickles bytes forever and a stalled
 * POST each fail with a transport error instead of hanging.
 */
class PollingTimeoutE2ETest {
    private fun timeoutCase(
        mode: String,
        expectedReason: String,
    ) = e2e("polling-request-timeout.mjs", mapOf("REQUEST_TIMEOUT_MODE" to mode)) {
        val client = EngineClient(url, EngineOptions(transports = listOf("polling"), requestTimeout = 300.milliseconds, path = "/engine.io"))
        try {
            client.open()
            if (mode == "post") {
                client.awaitOpen()
                client.onExecutor { send("stalls") }
            }
            val error = client.next<EngineEvent.Error>().error as TransportException
            assertEquals(expectedReason, error.message)
            assertTrue(error.cause is InterruptedIOException || error.cause?.cause is InterruptedIOException, "${error.cause}")
            assertEquals("transport error", client.awaitClose().reason)
        } finally {
            client.close()
        }
    }

    @Test
    fun aStalledHandshakeTimesOut() = timeoutCase("handshake", "xhr poll error")

    @Test
    fun aTricklingHandshakeTimesOut() = timeoutCase("trickle", "xhr poll error")

    @Test
    fun aStalledPostTimesOut() = timeoutCase("post", "xhr post error")
}
