package dev.shepherd.common

import java.util.Properties

/** Build metadata stamped into the jar by Gradle from `shepherdVersion` in gradle.properties. */
object BuildInfo {
    private const val RESOURCE = "/dev/shepherd/build-info.properties"
    private const val UNKNOWN_VERSION = "dev"

    /** The project version, or `dev` when running from classes that were not processed by Gradle. */
    val version: String by lazy {
        val properties = Properties()
        BuildInfo::class.java.getResourceAsStream(RESOURCE)?.use(properties::load)
        properties.getProperty("version")
            ?.takeIf { value -> value.isNotBlank() && !value.startsWith("\${") }
            ?: UNKNOWN_VERSION
    }
}
