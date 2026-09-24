plugins {
    id("socketio.kotlin-library")
}

description = "Engine.IO 4 client: engine socket, transports, heartbeat and upgrade"

dependencies {
    api(project(":engineio-parser"))
    api(project(":socketio-parser"))
    api(libs.coroutines.core)
    testImplementation(project(":socketio-testing"))
}
