// A published pure-JVM library module: explicit public API, API dump,
// coverage and Maven publication.
plugins {
    id("socketio.kotlin-base")
    id("org.jetbrains.kotlinx.kover")
    id("socketio.publish")
}

kotlin {
    explicitApi()
}

java {
    withSourcesJar()
}
