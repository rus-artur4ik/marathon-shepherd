plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "dev.shepherd"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
