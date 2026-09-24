plugins {
    alias(libs.plugins.bcv)
}

apiValidation {
    // Harnesses, tools and the sample app are not public API.
    ignoredProjects += listOf("e2e-tests", "parser-parity", "sample")
    nonPublicMarkers += "io.github.kaeferfreund.socketio.InternalSocketIOApi"
}
