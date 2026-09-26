// Maven publication with a complete POM. The Android convention registers the
// "release" component; JVM modules publish the "java" component.
plugins {
    `maven-publish`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

publishing {
    publications {
        register<MavenPublication>("maven") {
            artifactId = project.name
            afterEvaluate {
                from(components.findByName("release") ?: components.getByName("java"))
            }
            pom {
                name.set(project.name)
                description.set(provider { project.description ?: "Socket.IO 4 client for Kotlin and Android" })
                url.set("https://github.com/kaeferfreund/socket.io-client-kotlin")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                scm {
                    url.set("https://github.com/kaeferfreund/socket.io-client-kotlin")
                    connection.set("scm:git:https://github.com/kaeferfreund/socket.io-client-kotlin.git")
                }
                developers {
                    developer {
                        id.set("kaeferfreund")
                        name.set("kaeferfreund")
                    }
                }
            }
        }
    }
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
