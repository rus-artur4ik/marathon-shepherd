plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.shepherd.adapter.farm.ApplicationKt")
    applicationName = "adapter-farm"
}

dependencies {
    implementation(project(":adapter:api"))
    implementation(libs.kotlinx.serialization.json)

    // Farm adapter needs HTTP client to talk to local farm-server
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
