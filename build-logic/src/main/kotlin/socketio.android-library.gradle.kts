import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A published Android library module. AGP 9 compiles Kotlin itself.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlinx.kover")
    id("org.jmailen.kotlinter")
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
