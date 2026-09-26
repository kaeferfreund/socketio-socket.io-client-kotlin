// An independent Gradle build that depends on the published artifacts only
// (mavenLocal, or Maven Central with -PsocketioFromMavenCentral), never on the
// project modules. scripts/test-consumer.sh copies the README quick start into
// src/main/kotlin and compiles it.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!providers.gradleProperty("socketioFromMavenCentral").isPresent) {
            mavenLocal {
                content { includeGroup("io.github.kaeferfreund.socketio") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "socketio-consumer"
