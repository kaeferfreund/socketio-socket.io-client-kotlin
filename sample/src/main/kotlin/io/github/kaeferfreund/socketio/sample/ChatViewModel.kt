package io.github.kaeferfreund.socketio.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kaeferfreund.socketio.AckTimeoutException
import io.github.kaeferfreund.socketio.ConnectionState
import io.github.kaeferfreund.socketio.Socket
import io.github.kaeferfreund.socketio.SocketManager
import io.github.kaeferfreund.socketio.SocketManagerOptions
import io.github.kaeferfreund.socketio.android.BackgroundPolicy
import io.github.kaeferfreund.socketio.android.android
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** Owns one manager for the screen; everything the UI shows is a state flow. */
class ChatViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private var manager: SocketManager? = null
    private var socket: Socket? = null

    private val log = MutableStateFlow(listOf<String>())
    val messages: StateFlow<List<String>> = log.asStateFlow()

    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val state: StateFlow<ConnectionState> = connection.asStateFlow()

    private val transport = MutableStateFlow<String?>(null)
    val transportName: StateFlow<String?> = transport.asStateFlow()

    fun connect(url: String) {
        disconnect()
        val manager =
            SocketManager(
                url,
                SocketManagerOptions {
                    android(getApplication()) {
                        backgroundPolicy = BackgroundPolicy.DisconnectAfter(30.seconds)
                        trafficStatsTag = 0x5001
                    }
                },
            )
        val socket =
            manager.socket("/") {
                on("message") { event -> append("⬇ ${event.args.joinToString()}") }
                onConnectError { error -> append("connect_error: ${error.message}") }
                onDisconnect { reason, _ -> append("disconnect: ${reason.wireValue}") }
            }
        this.manager = manager
        this.socket = socket
        viewModelScope.launch { socket.state.collect { connection.value = it } }
        viewModelScope.launch { manager.transportName.collect { transport.value = it } }
    }

    fun send(text: String) {
        val socket = socket ?: return
        append("⬆ $text")
        viewModelScope.launch {
            try {
                val reply = socket.timeout(5.seconds).emitWithAck("message", text)
                append("ack: ${reply.joinToString()}")
            } catch (e: AckTimeoutException) {
                append("no acknowledgement within 5 s")
            } catch (e: Exception) {
                append("failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        manager?.close()
        manager = null
        socket = null
        connection.value = ConnectionState.Disconnected()
    }

    private fun append(line: String) {
        log.value = (log.value + line).takeLast(200)
    }

    override fun onCleared() {
        disconnect()
    }
}
