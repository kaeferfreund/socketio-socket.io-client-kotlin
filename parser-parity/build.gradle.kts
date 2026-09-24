plugins {
    id("socketio.kotlin-internal")
    application
}

application {
    mainClass.set("io.github.kaeferfreund.socketio.parity.ParserParityKt")
}

dependencies {
    implementation(project(":socketio-parser"))
}
