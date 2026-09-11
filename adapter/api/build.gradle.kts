plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":adapter:contract"))
    implementation(libs.kotlinx.serialization.json)

    // Shared adapter runtime (auth, server bootstrap, metrics)
    api(libs.ktor.server.core)
    api(libs.ktor.server.netty)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.server.call.logging)
    api(libs.ktor.server.auth)
    api(libs.ktor.server.metrics.micrometer)
    api(libs.ktor.serialization.json)
    api(libs.micrometer.registry.prometheus)
    api(libs.logback)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
