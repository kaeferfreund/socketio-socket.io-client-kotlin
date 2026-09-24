// A published pure-JVM library module: explicit public API, API dump,
// coverage and Maven publication.
plugins {
    id("socketio.kotlin-base")
    id("org.jetbrains.kotlinx.kover")
    id("socketio.publish")
}

// Consumers compile against this metadata: an app on the Kotlin version that
// AGP 9 bundles (2.2) must be able to read it, so the published modules target
// language and API version 2.2 and depend on the 2.2 standard library, whatever
// compiler builds them.
kotlin {
    coreLibrariesVersion = "2.2.20"
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

kotlin {
    explicitApi()
}

java {
    withSourcesJar()
}
