plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// The manager's HTTP API as plain serializable types. Shared by the service, the Kotlin
// client, mshctl and the MCP server so the wire format has exactly one definition.
dependencies {
    api(project(":adapter:contract"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
