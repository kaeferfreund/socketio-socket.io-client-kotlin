# The default HTTP/WebSocket stack is found with ServiceLoader; keep the provider.
-keep class io.github.kaeferfreund.socketio.okhttp.OkHttpEngineClientsProvider { <init>(); }
-keepnames class io.github.kaeferfreund.socketio.engineio.EngineClients$Provider
