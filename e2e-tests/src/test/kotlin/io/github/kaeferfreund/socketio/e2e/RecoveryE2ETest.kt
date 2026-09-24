package io.github.kaeferfreund.socketio.e2e

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Port of `packages/socket.io-client/test/connection-state-recovery.ts`. */
class RecoveryE2ETest {
    // JS-001
    @Test
    fun recoversTheSessionAfterATransportClose() =
        e2e {
            val socket = io { reconnectionDelay = 10.milliseconds }
            assertFalse(socket.recovered)
            val hi = socket.channel("hi")
            socket.emit("hi") // init the offset
            hi.next()
            val id = socket.id
            val reconnected = CompletableDeferred<Pair<String?, Boolean>>()
            socket.onConnect { reconnected.complete(socket.id to socket.recovered) }
            killTransport(socket)
            val (newId, recovered) = withTimeout(10.seconds) { reconnected.await() }
            assertEquals(id, newId)
            assertTrue(recovered)
            socket.disconnect()
        }
}
