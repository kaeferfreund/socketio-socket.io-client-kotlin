plugins {
    id("socketio.kotlin-internal")
}

dependencies {
    testImplementation(project(":socketio-okhttp"))
    testImplementation(project(":socketio-testing"))
    testImplementation(libs.okhttp.tls)
}

tasks.test {
    // Real servers, real sockets, real time: every E2E class spawns its own Node fixture.
    systemProperty("socketio.fixtures", rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("fixtures")).withPathSensitivity(PathSensitivity.RELATIVE).optional()
    maxParallelForks = 1
    maxHeapSize = "1g"
}
