import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared by every pure-JVM module: Kotlin 2 with warnings as errors, JVM 17
// bytecode (Android D8 and every supported JDK read it), JUnit Platform tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jmailen.kotlinter")
    id("dev.detekt")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjdk-release=17")
        // Modules of this repository may use each other's internal API.
        if (project.name !in setOf("engineio-parser", "socketio-parser", "parser-parity")) {
            optIn.add("io.github.kaeferfreund.socketio.engineio.InternalSocketIOApi")
        }
    }
}

// Tests drive virtual time with the experimental kotlinx-coroutines-test API.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    "testImplementation"(libs.findLibrary("coroutines-test").get())
    "testImplementation"(kotlin("test-junit5"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = providers.gradleProperty("showTestOutput").isPresent
    }
    // A skipped test is never evidence: the parity gate (CI, or
    // check-parity-contracts.py --strict --junit) rejects any skipped or failed
    // test in the JUnit XML. With extension autodetection off, no extension on
    // the classpath can switch tests off unnoticed.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt.yml"))
    parallel = true
}
