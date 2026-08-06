import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
}

allprojects {
    group = "dev.shepherd"
    // Single source of truth: `shepherdVersion` in gradle.properties. Release
    // tooling overrides it with `-PshepherdVersion=x.y.z`.
    version = providers.gradleProperty("shepherdVersion").get()

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // Only the plain reporter. The default set includes SARIF, which drags in an extra
    // dependency (sarif4k) that `./gradlew build` would then have to resolve — turning a
    // transient network hiccup into an opaque "Error while saving task graph" for anyone
    // building from a clean clone. Nothing here consumes SARIF output.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension>("ktlint") {
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Pin the toolchain so the bytecode target never silently follows
        // whatever JDK a contributor happens to have on PATH. All runtime
        // images are Temurin 21-jre.
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(21)
        }
        // Every module runs JUnit 5; declaring it centrally means a module that
        // gains its first test cannot silently run zero tests.
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
