plugins {
    id("socketio.kotlin-library")
}

description = "Test utilities for Socket.IO client users: fake transports, packet recorder, fixture server launcher"

dependencies {
    api(project(":engineio-client"))
    api(libs.coroutines.core)
    compileOnly(project(":socketio-parser"))
}
