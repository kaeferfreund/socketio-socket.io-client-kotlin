plugins {
    id("socketio.kotlin-serialization")
}

description = "kotlinx.serialization adapter for Socket.IO values"

dependencies {
    api(project(":socketio-client"))
    api(libs.serialization.json)
}
