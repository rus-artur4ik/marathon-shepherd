plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    // Shared adapter runtime (auth, server bootstrap)
    api(libs.ktor.server.core)
    api(libs.ktor.server.netty)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.server.call.logging)
    api(libs.ktor.server.auth)
    api(libs.ktor.serialization.json)
    api(libs.logback)
}
