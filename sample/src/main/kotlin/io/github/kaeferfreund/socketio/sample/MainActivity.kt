package io.github.kaeferfreund.socketio.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kaeferfreund.socketio.ConnectionState

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { ChatScreen(viewModel) }
            }
        }
    }
}

@Composable
private fun ChatScreen(viewModel: ChatViewModel) {
    // 10.0.2.2 is the emulator's alias for the host running `node fixtures/server.js`.
    var url by rememberSaveable { mutableStateOf("http://10.0.2.2:3000") }
    var text by rememberSaveable { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    val transport by viewModel.transportName.collectAsState()
    val messages by viewModel.messages.collectAsState()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.connect(url) }) { Text("Connect") }
            Button(onClick = viewModel::disconnect) { Text("Disconnect") }
        }
        Text(
            when (val current = state) {
                is ConnectionState.Connected -> "Connected as ${current.id} over ${transport ?: "…"}" + if (current.recovered) " (recovered)" else ""
                ConnectionState.Connecting -> "Connecting…"
                is ConnectionState.Disconnected -> "Disconnected" + (current.reason?.let { ": ${it.wireValue}" } ?: "")
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, label = { Text("Message") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                viewModel.send(text)
                text = ""
            }) { Text("Send") }
        }
        LazyColumn(Modifier.fillMaxSize()) { items(messages) { Text(it) } }
    }
}
