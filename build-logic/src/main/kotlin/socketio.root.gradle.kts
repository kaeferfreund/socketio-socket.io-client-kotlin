// Root project: API compatibility of the published JVM modules. Android modules
// get equivalent apiDump/apiCheck tasks from socketio.android-library.
plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    id("org.jetbrains.kotlinx.kover")
}

apiValidation {
    // Harnesses, tools and the sample app are not public API; Android modules use their own tasks.
    ignoredProjects += listOf("e2e-tests", "parser-parity", "sample", "socketio-android")
    nonPublicMarkers += "io.github.kaeferfreund.socketio.engineio.InternalSocketIOApi"
}

// Merged coverage of the JVM library modules across every JVM test suite, including
// the end-to-end suites (./gradlew :koverXmlReport). The Android module reports on its own.
dependencies {
    for (module in listOf("engineio-parser", "socketio-parser", "engineio-client", "socketio-client", "socketio-okhttp", "socketio-serialization", "e2e-tests")) {
        "kover"(project(":$module"))
    }
}

kover {
    reports {
        filters {
            excludes {
                // Test support, not library behaviour.
                packages("io.github.kaeferfreund.socketio.testing")
            }
        }
    }
}
