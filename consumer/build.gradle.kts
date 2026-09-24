plugins {
    id("com.android.library") version "9.4.1"
}

val socketioVersion = providers.gradleProperty("socketioVersion").get()

android {
    namespace = "io.github.kaeferfreund.socketio.consumer"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation("io.github.kaeferfreund.socketio:socketio-android:$socketioVersion")
}
