package io.github.kaeferfreund.socketio.e2e

import io.github.kaeferfreund.socketio.engineio.CloseDetails
import io.github.kaeferfreund.socketio.engineio.EngineEvent
import io.github.kaeferfreund.socketio.engineio.EngineIOException
import io.github.kaeferfreund.socketio.engineio.EngineOptions
import io.github.kaeferfreund.socketio.engineio.EngineSocket
import io.github.kaeferfreund.socketio.engineio.TransportException
import io.github.kaeferfreund.socketio.engineio.TransportOverrides
import io.github.kaeferfreund.socketio.engineio.parser.EngineIOData
import io.github.kaeferfreund.socketio.parser.SocketIOJson
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ports of the Node-executed `packages/engine.io-client/test` suites
 * (`connection.js`, `socket.js`, `transport.js`, `node.js`, `arraybuffer/`,
 * `blob/`) against the ported upstream engine server (maxHttpBufferSize 100,
 * pingInterval 500, a `deny` query that refuses requests).
 */
class EngineE2ETest {
    private val polling = listOf("polling")
    private val websocket = listOf("websocket")

    @BeforeEach
    fun forgetWebSocketSuccess() {
        EngineSocket.priorWebsocketSuccess.set(false)
    }

    // JS-145
    @Test
    fun connectsToLocalhost() =
        engineE2E {
            val client = engine()
            client.awaitOpen()
            assertEquals("hi", client.nextText())
        }

    // JS-146
    @Test
    fun connectsToLocalhostOverWebSocket() =
        engineE2E {
            val client = engine(EngineOptions(transports = websocket))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
        }

    // JS-147
    @Test
    fun receivesMultibyteUtf8StringsWithPolling() =
        engineE2E {
            val client = engine()
            client.awaitOpen()
            client.onExecutor { send("cash money €€€") }
            assertEquals("hi", client.nextText())
            assertEquals("cash money €€€", client.nextText())
        }

    // JS-148
    @Test
    fun receivesEmoji() =
        engineE2E {
            val emoji = "𐀀-󯿿󰀀-􏿿-"
            val client = engine()
            client.awaitOpen()
            client.onExecutor { send(emoji) }
            assertEquals("hi", client.nextText())
            assertEquals(emoji, client.nextText())
        }

    // JS-149
    @Test
    fun doesNotSendPacketsIfTheSocketCloses() =
        engineE2E {
            val client = engine()
            client.awaitOpen()
            var created = 0
            client.onExecutor {
                events.on(EngineEvent.PacketCreated::class.java) { created++ }
                close()
                send("hi")
            }
            delay(200.milliseconds)
            assertEquals(0, client.onExecutor { created })
        }

    // JS-150
    @Test
    fun mergesPacketsAccordingToMaxPayload() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling))
            client.awaitOpen()
            client.onExecutor {
                for (text in listOf("a".repeat(99), "b".repeat(30), "c".repeat(30), "d".repeat(35), "€".repeat(33), "f".repeat(99))) send(text)
            }
            assertEquals("hi", client.nextText())
            repeat(6) { client.nextText() }
        }

    // JS-151
    @Test
    fun sendsAPacketAboveMaxPayloadAnyway() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling))
            client.awaitOpen()
            client.onExecutor {
                send("a".repeat(101))
                send("b")
            }
            client.awaitClose()
        }

    // JS-153
    @Test
    fun defersCloseWhenUpgrading() =
        engineE2E {
            val client = engine(open = false)
            var upgraded = false
            val closedAfterUpgrade = kotlinx.coroutines.CompletableDeferred<Boolean>()
            client.onExecutor {
                events.on(EngineEvent.Upgrade::class.java) { upgraded = true }
                events.on(EngineEvent.Upgrading::class.java) {
                    events.on(EngineEvent.Close::class.java) { closedAfterUpgrade.complete(upgraded) }
                    close()
                }
                open()
            }
            assertTrue(kotlinx.coroutines.withTimeout(10_000) { closedAfterUpgrade.await() })
        }

    // JS-155
    @Test
    fun doesNotSendPacketsIfClosingIsDeferred() =
        engineE2E {
            val client = engine(open = false)
            var created = 0
            client.onExecutor {
                events.on(EngineEvent.Upgrading::class.java) {
                    events.on(EngineEvent.PacketCreated::class.java) { created++ }
                    close()
                    send("hi")
                }
                open()
            }
            delay(300.milliseconds)
            assertEquals(0, client.onExecutor { created })
        }

    // JS-156
    @Test
    fun sendsAllBufferedPacketsIfClosingIsDeferred() =
        engineE2E {
            val client = engine(open = false)
            val bufferAtClose = kotlinx.coroutines.CompletableDeferred<Int>()
            client.onExecutor {
                events.on(EngineEvent.Upgrading::class.java) {
                    send("hi")
                    close()
                }
                events.on(EngineEvent.Close::class.java) { bufferAtClose.complete(writeBuffer.size) }
                open()
            }
            assertEquals(0, kotlinx.coroutines.withTimeout(10_000) { bufferAtClose.await() })
        }

    // JS-177, JS-134, JS-141
    @Test
    fun mergesBinaryPacketsAccordingToMaxPayload() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling))
            client.awaitOpen()
            client.onExecutor {
                send(EngineIOData.Binary(ByteArray(72)))
                send(EngineIOData.Binary(ByteArray(20)))
                send("a".repeat(20))
                send(EngineIOData.Binary(ByteArray(20)))
                send(EngineIOData.Binary(ByteArray(72)))
            }
            assertEquals("hi", client.nextText())
            repeat(5) { client.nextMessage() }
        }

    // JS-178
    @Test
    fun sendsCookiesWithWithCredentials() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling, withCredentials = true))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
            delay(10.milliseconds)
            client.onExecutor { send("sendHeaders") }
            val headers = SocketIOJson.parse(client.nextText()).obj!!
            assertEquals("1=1; 2=2", headers["cookie"]!!.string)
        }

    // JS-179
    @Test
    fun doesNotSendCookiesWithoutWithCredentials() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling, withCredentials = false))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
            client.onExecutor { send("sendHeaders") }
            val headers = SocketIOJson.parse(client.nextText()).obj!!
            assertEquals(null, headers["cookie"])
        }

    // JS-187
    @Test
    fun connectsWithTheSecondTransportIfTryAllTransports() =
        engineE2E {
            val client =
                engine(
                    EngineOptions(
                        transports = listOf("websocket", "polling"),
                        transportOptions = mapOf("websocket" to TransportOverrides(query = mapOf("deny" to "1"))),
                        tryAllTransports = true,
                    ),
                )
            client.awaitOpen()
            assertEquals("polling", client.onExecutor { transport!!.name })
        }

    // JS-188
    @Test
    fun connectsWithTheSecondTransportIfTryAllTransportsAndPollingFails() =
        engineE2E {
            val client =
                engine(
                    EngineOptions(
                        transports = listOf("polling", "websocket"),
                        transportOptions = mapOf("polling" to TransportOverrides(query = mapOf("deny" to "1"))),
                        tryAllTransports = true,
                    ),
                )
            client.awaitOpen()
            assertEquals("websocket", client.onExecutor { transport!!.name })
        }

    // JS-189
    @Test
    fun doesNotConnectWithTheSecondTransportByDefault() =
        engineE2E {
            val client =
                engine(
                    EngineOptions(
                        transports = listOf("polling", "websocket"),
                        transportOptions = mapOf("polling" to TransportOverrides(query = mapOf("deny" to "1"))),
                    ),
                )
            assertEquals("xhr poll error", client.next<EngineEvent.Error>().error.message)
        }

    // JS-190
    @Test
    fun connectsWithACustomPollingTransport() =
        engineE2E {
            val client =
                engine(
                    EngineOptions(
                        transports = polling,
                        transportFactories = mapOf("polling" to io.github.kaeferfreund.socketio.engineio.PollingTransport.FACTORY),
                    ),
                )
            client.awaitOpen()
            assertEquals("polling", client.onExecutor { transport!!.name })
        }

    // JS-191
    @Test
    fun connectsWithACustomWebSocketTransport() =
        engineE2E {
            val client =
                engine(
                    EngineOptions(
                        transports = websocket,
                        transportFactories = mapOf("websocket" to io.github.kaeferfreund.socketio.engineio.WebSocketTransport.FACTORY),
                    ),
                )
            client.awaitOpen()
            assertEquals("websocket", client.onExecutor { transport!!.name })
        }

    // JS-194
    @Test
    fun providesDetailsWhenMaxHttpBufferSizeIsReachedOverPolling() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling))
            client.awaitOpen()
            client.onExecutor { send("a".repeat(101)) }
            val error = client.next<EngineEvent.Error>().error as TransportException
            assertEquals("TransportError", error.type)
            assertEquals(413, error.description)
            assertEquals("xhr post error", error.message)
            assertEquals("", error.responseBody)
            val close = client.awaitClose()
            assertEquals("transport error", close.reason)
            assertTrue(close.description is EngineIOException)
        }

    // JS-195
    @Test
    fun providesDetailsWhenMaxHttpBufferSizeIsReachedOverWebSocket() =
        engineE2E {
            val client = engine(EngineOptions(transports = websocket))
            client.awaitOpen()
            client.onExecutor { send("a".repeat(101)) }
            val close = client.awaitClose()
            assertEquals("transport close", close.reason)
            val details = close.description as CloseDetails
            assertEquals("websocket connection closed", details.description)
            assertEquals(1009, details.code)
            assertEquals("", details.reason)
        }

    // JS-196
    @Test
    fun providesDetailsWhenTheSessionIdIsUnknownOverPolling() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling, query = mapOf("sid" to "abc")))
            val error = client.next<EngineEvent.Error>().error as TransportException
            assertEquals("xhr poll error", error.message)
            assertEquals(400, error.description)
            assertEquals("{\"code\":1,\"message\":\"Session ID unknown\"}", error.responseBody)
            assertEquals("transport error", client.awaitClose().reason)
        }

    // JS-197
    @Test
    fun providesDetailsWhenTheSessionIdIsUnknownOverWebSocket() =
        engineE2E {
            val client = engine(EngineOptions(transports = websocket, query = mapOf("sid" to "abc")))
            val error = client.next<EngineEvent.Error>().error as TransportException
            assertEquals("websocket error", error.message)
            assertEquals("transport error", client.awaitClose().reason)
        }

    // JS-199
    @Test
    fun remembersAWebSocketConnection() =
        engineE2E {
            val first = engine()
            first.next<EngineEvent.Upgrade>()
            first.onExecutor { close() }
            val second = engine(EngineOptions(rememberUpgrade = true), open = false)
            assertEquals("websocket", second.onExecutor { open().let { transport!!.name } })
        }

    // JS-200
    @Test
    fun doesNotRememberAWebSocketConnectionByDefault() =
        engineE2E {
            val first = engine()
            first.next<EngineEvent.Upgrade>()
            first.onExecutor { close() }
            val second = engine(EngineOptions(rememberUpgrade = false), open = false)
            assertEquals("polling", second.onExecutor { open().let { transport!!.name } })
        }

    // JS-131
    @Test
    fun receivesBinaryDataWhenBouncingItBackOverPolling() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
            val binary = ByteArray(5) { it.toByte() }
            client.onExecutor { send(EngineIOData.Binary(binary)) }
            assertArrayEquals(binary, (client.nextMessage() as EngineIOData.Binary).bytes)
        }

    // JS-132
    @Test
    fun receivesBinaryDataAndAMultibyteStringOverPolling() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
            val binary = ByteArray(5) { it.toByte() }
            client.onExecutor {
                send(EngineIOData.Binary(binary))
                send("cash money €€€")
            }
            assertArrayEquals(binary, (client.nextMessage() as EngineIOData.Binary).bytes)
            assertEquals("cash money €€€", client.nextText())
        }

    // JS-133
    @Test
    fun receivesBinaryDataWhenForcingBase64OverPolling() =
        engineE2E {
            val client = engine(EngineOptions(transports = polling, forceBase64 = true))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
            val binary = ByteArray(5) { it.toByte() }
            client.onExecutor { send(EngineIOData.Binary(binary)) }
            assertArrayEquals(binary, (client.nextMessage() as EngineIOData.Binary).bytes)
        }

    // JS-135
    @Test
    fun receivesBinaryDataWhenBouncingItBackOverWebSocket() =
        engineE2E {
            val client = engine(EngineOptions(transports = websocket))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
            val binary = ByteArray(5) { it.toByte() }
            client.onExecutor { send(EngineIOData.Binary(binary)) }
            assertArrayEquals(binary, (client.nextMessage() as EngineIOData.Binary).bytes)
        }

    // JS-136: sent after the polling → WebSocket upgrade, as in the original.
    @Test
    fun receivesBinaryDataAndAMultibyteStringAfterUpgrading() =
        engineE2E {
            val client = engine()
            client.next<EngineEvent.Upgrade>()
            assertEquals("hi", client.nextText())
            val binary = ByteArray(5) { it.toByte() }
            client.onExecutor {
                send(EngineIOData.Binary(binary))
                send("cash money €€€")
            }
            assertArrayEquals(binary, (client.nextMessage() as EngineIOData.Binary).bytes)
            assertEquals("cash money €€€", client.nextText())
        }

    // JS-137
    @Test
    fun receivesBinaryDataWhenForcingBase64OverWebSocket() =
        engineE2E {
            val client = engine(EngineOptions(transports = websocket, forceBase64 = true))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
            val binary = ByteArray(5) { it.toByte() }
            client.onExecutor { send(EngineIOData.Binary(binary)) }
            assertArrayEquals(binary, (client.nextMessage() as EngineIOData.Binary).bytes)
        }

    // JS-139, JS-142: the server's `give binary` reply (an Int8Array) arrives as binary.
    @Test
    fun receivesServerBinaryOverPollingAndWebSocket() =
        engineE2E {
            for (transports in listOf(polling, websocket)) {
                val client = engine(EngineOptions(transports = transports))
                client.awaitOpen()
                assertEquals("hi", client.nextText())
                client.onExecutor { send("give binary") }
                assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), (client.nextMessage() as EngineIOData.Binary).bytes)
            }
        }

    // JS-140, JS-143, JS-144: a browser Blob is bytes; sent and echoed over polling, WebSocket and WebSocket with Base64.
    @Test
    fun sendsBinaryAndGetsItBackOnEveryTransport() =
        engineE2E {
            for ((transports, base64) in listOf(polling to false, websocket to false, websocket to true)) {
                val client = engine(EngineOptions(transports = transports, forceBase64 = base64))
                client.awaitOpen()
                assertEquals("hi", client.nextText())
                val blob = byteArrayOf(0, 1, 2, 3, 4)
                client.onExecutor { send(EngineIOData.Binary(blob)) }
                assertArrayEquals(blob, (client.nextMessage() as EngineIOData.Binary).bytes)
            }
        }

    // JS-219, JS-220, JS-223: extra headers reach the server on polling (echoed by `sendHeaders`).
    @Test
    fun setsExtraHeadersPerTransport() =
        engineE2E {
            val headers = mapOf("X-Custom-Header-For-My-Project" to "my-secret-access-token")
            val client = engine(EngineOptions(transports = polling, transportOptions = mapOf("polling" to TransportOverrides(extraHeaders = headers))))
            client.awaitOpen()
            assertEquals("hi", client.nextText())
            client.onExecutor { send("sendHeaders") }
            val echoed = SocketIOJson.parse(client.nextText()).obj!!
            assertEquals("my-secret-access-token", echoed["x-custom-header-for-my-project"]!!.string)
        }

    @Test
    fun heartbeatsKeepTheConnectionAlive() =
        engineE2E {
            val client = engine()
            client.awaitOpen()
            // The server pings every 500 ms; three pings prove the pong path over polling and WebSocket.
            repeat(3) { client.next<EngineEvent.Ping>(timeout = kotlin.time.Duration.parse("5s")) }
            assertEquals(io.github.kaeferfreund.socketio.engineio.EngineState.OPEN, client.onExecutor { readyState })
        }
}
