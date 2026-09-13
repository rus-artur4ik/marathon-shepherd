plugins {
    kotlin("jvm")
    // The credentials file mshctl login writes.
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.shepherd.cli.MainKt")
    applicationName = "mshctl"
}

dependencies {
    implementation(project(":manager:client"))
    implementation(libs.clikt)
    // The HTTP client logs through SLF4J; a CLI has nowhere useful to send it.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
