// Pure Kotlin/JVM module: the GVRET protocol parser, CSV writer, and the
// fake GVRET server used to test the connection layer — no Android
// dependencies, so it runs as plain JVM unit tests (fast, no emulator).
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
}
