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

    // Self-registration with the manager. The client type is part of ManagerRegistration's
    // constructor, so it is `api`; the engine and JSON plugin are implementation details.
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
