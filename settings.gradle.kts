pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "socket.io-client-kotlin"

include(
    ":engineio-parser",
    ":socketio-parser",
    ":engineio-client",
    ":socketio-client",
    ":socketio-okhttp",
    ":socketio-android",
    ":socketio-serialization",
    ":socketio-testing",
    ":e2e-tests",
    ":parser-parity",
    ":sample",
)
