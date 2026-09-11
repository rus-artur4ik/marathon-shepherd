plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Kotlin client for the manager API; mshctl and the MCP server build on it.
dependencies {
    api(project(":manager:protocol"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
