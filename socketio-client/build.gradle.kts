plugins {
    id("socketio.kotlin-library")
}

description = "Socket.IO 4 client: manager, sockets, acknowledgements, retries and recovery"

dependencies {
    api(project(":engineio-client"))
    api(project(":socketio-parser"))
    api(libs.coroutines.core)
    testImplementation(project(":socketio-testing"))
    testImplementation(libs.lincheck)
}
