plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.kotlinter.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.kotlin.compose.plugin)
    implementation(libs.bcv.library)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
}
