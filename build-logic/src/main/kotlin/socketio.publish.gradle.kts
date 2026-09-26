// Maven publication with a complete POM, sources and Dokka javadoc, signed, to
// Maven Central and GitHub Packages. The plugin detects the Android library or
// Kotlin JVM plugin and publishes its "release" variant or "java" component.
plugins {
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

// Warnings are errors, in KDoc as in code: an unresolved link fails the javadoc jar.
dokka {
    dokkaPublications.configureEach { failOnWarning.set(true) }
}

mavenPublishing {
    publishToMavenCentral()
    // Without a key (publishToMavenLocal, the consumer job) the artifacts stay
    // unsigned; Maven Central rejects an unsigned deployment during validation.
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }
    pom {
        name.set(project.name)
        description.set(provider { project.description ?: "Socket.IO 4 client for Kotlin and Android" })
        inceptionYear.set("2026")
        url.set("https://github.com/kaeferfreund/socket.io-client-kotlin")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        scm {
            url.set("https://github.com/kaeferfreund/socket.io-client-kotlin")
            connection.set("scm:git:https://github.com/kaeferfreund/socket.io-client-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/kaeferfreund/socket.io-client-kotlin.git")
        }
        developers {
            developer {
                id.set("kaeferfreund")
                name.set("kaeferfreund")
                url.set("https://github.com/kaeferfreund")
            }
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/kaeferfreund/socket.io-client-kotlin")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
