plugins {
    id("socketio.android-library")
}

description = "Android integration: network callbacks and binding, lifecycle policy, KeyChain mTLS, logging, traffic tagging"

android {
    namespace = "io.github.kaeferfreund.socketio.android"
}

dependencies {
    api(project(":socketio-okhttp"))
    api(libs.coroutines.android)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.tracing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(project(":socketio-testing"))
    testImplementation(libs.androidx.lifecycle.testing)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
}
