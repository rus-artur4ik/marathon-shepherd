plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.shepherd.ApplicationKt")
    applicationName = "manager"
}

dependencies {
    implementation(project(":manager:protocol"))
    // The MCP tools served at /mcp. The binary's stderr logger stays out; the manager logs with logback.
    implementation(project(":manager:mcp")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.swagger)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.ktor.serialization.json)

    // Ktor client (adapter communication)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Serialization & config
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    // SQLite
    implementation(libs.sqlite.jdbc)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    // Logging
    implementation(libs.logback)

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(project(":manager:client"))
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.lincheck)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED"
    )
}
