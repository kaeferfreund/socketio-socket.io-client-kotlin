package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.Transport
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmokeE2ETest {
    @Test
    fun connectsEmitsAndAcknowledgesOverEveryTransportMode() {
        for (transports in listOf(listOf(Transport.POLLING), listOf(Transport.WEBSOCKET), listOf(Transport.POLLING, Transport.WEBSOCKET))) {
            e2e {
                val socket = manager { this.transports = transports }.socket()
                val hi = socket.channel("hi")
                socket.awaitConnect()
                socket.emit("hi")
                hi.next()
                assertEquals(listOf("ok"), socket.emitWithAck("echo", "ok").map { it.string })
                val doge = socket.channel("doge")
                socket.emit("doge")
                assertArrayEquals("asdfasdf".encodeToByteArray(), doge.next()[0]!!.bytes)
                if (transports.size == 2) {
                    while (socket.manager.transportName.value != Transport.WEBSOCKET) delay(10)
                }
                assertTrue(socket.connected)
                socket.disconnect()
            }
        }
    }
}
