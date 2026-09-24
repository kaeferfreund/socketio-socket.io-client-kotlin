// Root project: API compatibility of the published JVM modules. Android modules
// get equivalent apiDump/apiCheck tasks from socketio.android-library.
plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

apiValidation {
    // Harnesses, tools and the sample app are not public API; Android modules use their own tasks.
    ignoredProjects += listOf("e2e-tests", "parser-parity", "sample", "socketio-android")
    nonPublicMarkers += "io.github.kaeferfreund.socketio.engineio.InternalSocketIOApi"
}
