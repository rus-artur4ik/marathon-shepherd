plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.shepherd.mcp.MainKt")
    applicationName = "shepherd-mcp"
}

// MCP tools over ShepherdApi. The shepherd-mcp binary serves them over stdio to a local agent;
// the manager serves the same tools at /mcp.
dependencies {
    api(project(":manager:protocol"))
    api(libs.mcp.kotlin.sdk.server)
    implementation(project(":manager:client"))
    implementation(libs.kotlinx.serialization.json)
    // stdio carries the protocol on stdout, so the binary must log to stderr, which is where
    // slf4j-simple writes. The manager excludes it and keeps logback.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
