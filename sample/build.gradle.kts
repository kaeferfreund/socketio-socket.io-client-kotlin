plugins {
    id("socketio.android-application")
}

android {
    namespace = "io.github.kaeferfreund.socketio.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.kaeferfreund.socketio.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
        optIn.add("io.github.kaeferfreund.socketio.engineio.InternalSocketIOApi")
    }
}

dependencies {
    implementation(project(":socketio-android"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}
