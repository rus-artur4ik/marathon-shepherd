plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Wire types shared by the manager and every adapter, with no server runtime attached, so
// clients (the manager, the CLI, the MCP server) can depend on them without pulling Netty.
dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Stamp the project version into the jar so every service reports the version it was
// actually built as, instead of a constant that drifts from gradle.properties.
// The value is read into a local so the file-matching action captures only a String;
// referencing a script-level property would drag the build script into the
// configuration cache, which it cannot serialize.
tasks.processResources {
    val shepherdVersion: String = project.version.toString()
    inputs.property("shepherdVersion", shepherdVersion)
    filesMatching("dev/shepherd/build-info.properties") {
        expand(mapOf("version" to shepherdVersion))
    }
}
