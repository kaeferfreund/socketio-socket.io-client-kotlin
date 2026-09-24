plugins {
    id("socketio.kotlin-internal")
}

dependencies {
    testImplementation(project(":socketio-okhttp"))
    testImplementation(project(":socketio-testing"))
    testImplementation(libs.okhttp.tls)
}
