plugins {
    id("socketio.kotlin-library")
}

description = "OkHttp polling and WebSocket transports, TLS policy and cookies for the Socket.IO client"

dependencies {
    api(project(":socketio-client"))
    api(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(project(":socketio-testing"))
}
