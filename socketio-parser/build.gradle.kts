plugins {
    id("socketio.kotlin-library")
}

description = "Socket.IO protocol 5 packet codec with a bounded JSON value model"

dependencies {
    api(project(":engineio-parser"))
    testImplementation(libs.kotest.property)
}
