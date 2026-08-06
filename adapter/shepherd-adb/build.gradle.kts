plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.shepherd.adapter.adb.ApplicationKt")
    applicationName = "shepherd-adb"
}

dependencies {
    implementation(project(":adapter:api"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.lincheck)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Lincheck instruments bytecode at runtime — requires access to JDK internals on JDK 17+.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED"
    )
}
