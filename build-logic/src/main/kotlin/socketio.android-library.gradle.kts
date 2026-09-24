import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A published Android library module. AGP 9 compiles Kotlin itself.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlinx.kover")
    id("org.jmailen.kotlinter")
    id("dev.detekt")
    id("socketio.publish")
}

val libs = the<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = false
        unitTests.all { test ->
            test.useJUnitPlatform()
            // Robolectric sandboxes for SDK 35+ need Java 21; the library still targets Java 17.
            test.javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            // Robolectric's native runtime (SDK 35+) has no Linux/aarch64 build.
            val arch = System.getProperty("os.arch")
            if (System.getProperty("os.name").startsWith("Linux") && (arch == "aarch64" || arch == "arm64")) {
                test.systemProperty("robolectric.enabledSdks", "34")
            }
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
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
    compilerOptions {
        allWarningsAsErrors.set(true)
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("io.github.kaeferfreund.socketio.engineio.InternalSocketIOApi")
    }
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-vintage").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    "testImplementation"(libs.findLibrary("junit4").get())
    "testImplementation"(libs.findLibrary("coroutines-test").get())
}

// Public API dump and check, like the JVM modules (see AndroidApiTasks.kt).
val releaseClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes")
val apiFile = layout.projectDirectory.file("api/${project.name}.api")
tasks.register<AndroidApiDumpTask>("apiDump") {
    group = "verification"
    description = "Writes the public API of the release classes to api/${project.name}.api"
    dependsOn("compileReleaseKotlin")
    classes.set(releaseClasses)
    dumpFile.set(apiFile)
}
val apiCheck =
    tasks.register<AndroidApiCheckTask>("apiCheck") {
        group = "verification"
        description = "Fails when the public API differs from api/${project.name}.api"
        dependsOn("compileReleaseKotlin")
        classes.set(releaseClasses)
        dumpFile.set(apiFile)
    }
tasks.named("check") { dependsOn(apiCheck) }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt.yml"))
    parallel = true
}
