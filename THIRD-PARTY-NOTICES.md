# Third-party notices

Marathon Shepherd itself is licensed under [Apache-2.0](LICENSE). This file
records the third-party components it depends on, bundles into its distributions
and container images, or downloads at test time.

Versions are those pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
and the [`deploy/`](deploy) Dockerfiles at the time of writing; consult those
files for the authoritative list.

## Compile and runtime dependencies (bundled into distributions and images)

| Component | Version | License |
| --- | --- | --- |
| [Kotlin stdlib](https://github.com/JetBrains/kotlin) | 2.2.21 | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | Apache-2.0 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.8.1 | Apache-2.0 |
| [Ktor](https://github.com/ktorio/ktor) (server + client) | 2.3.13 | Apache-2.0 |
| [kaml](https://github.com/charleskorn/kaml) | 0.67.0 | Apache-2.0 |
| [Exposed](https://github.com/JetBrains/Exposed) | 0.58.0 | Apache-2.0 |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | 3.49.1.0 | Apache-2.0 |
| [Clikt](https://github.com/ajalt/clikt) | 5.0.2 | Apache-2.0 |
| [Logback](https://github.com/qos-ch/logback) | 1.5.18 | **EPL-1.0 OR LGPL-2.1** (dual) |
| [SLF4J](https://www.slf4j.org/) | transitive | MIT |

> **Logback** is dual-licensed under EPL-1.0 and LGPL-2.1. It is used as an
> unmodified library via dynamic linking, which both licenses permit without
> imposing their terms on this project. Replace it with any other SLF4J backend
> if your distribution policy requires that.

## Test-only dependencies (never shipped)

| Component | Version | License |
| --- | --- | --- |
| [kotlin-test](https://github.com/JetBrains/kotlin) | 2.2.21 | Apache-2.0 |
| [JUnit Platform](https://github.com/junit-team/junit5) | 1.11.4 | EPL-2.0 |
| [Lincheck](https://github.com/JetBrains/lincheck) | 2.34 | Apache-2.0 |
| [k6](https://github.com/grafana/k6) (optional scale driver) | external | AGPL-3.0 |
| [PyYAML](https://pyyaml.org/) | external | MIT |

> **k6** is AGPL-3.0. It is invoked as a separate, unmodified external binary
> that the user installs themselves — it is never bundled, linked, or
> redistributed here, so its terms do not extend to this project. The scale
> tier falls back to a pure-Python driver when k6 is absent.

## Container base images

| Image | Used by | License |
| --- | --- | --- |
| `eclipse-temurin:21-jre` | manager and adapter images | GPLv2 with Classpath Exception (OpenJDK) |
| `ubuntu:24.04` | `deploy/Dockerfile.adb-server` | Various; see Ubuntu licensing |
| `gcr.io/distroless/base` | `deploy/Dockerfile.cloud-orchestrator` | Apache-2.0 |

> `deploy/Dockerfile.adb-server` builds a **modified** Ubuntu image. Canonical's
> [trademark policy](https://ubuntu.com/legal/intellectual-property-policy)
> restricts redistributing modified images under the Ubuntu name. The image is
> published here under a neutral name and is intended for local test use; rename
> or rebase it before redistributing it commercially.

## Components fetched at build or test time (never committed)

| Component | Used by | License |
| --- | --- | --- |
| [Android SDK platform-tools (`adb`)](https://developer.android.com/tools/releases/platform-tools) | adb adapter image | Android SDK Terms |
| [google/cloud-android-orchestration](https://github.com/google/cloud-android-orchestration) | `deploy/Dockerfile.cloud-orchestrator` | Apache-2.0 |
| [android/architecture-samples](https://github.com/android/architecture-samples) | `scripts/build_test_apk.sh` sample APKs | Apache-2.0 |
| [Gradle](https://gradle.org/) distribution | `./gradlew` | Apache-2.0 |

> The **architecture-samples** APKs are built from a pinned upstream commit by
> `scripts/build_test_apk.sh` into the git-ignored `tests/public_ui/apks/`
> directory. They are debug builds of a modified checkout (the build script
> adjusts the AndroidX test runner version) and are **not** redistributed by
> this repository. Their upstream source is Apache-2.0; see
> [`tests/public_ui/README.md`](tests/public_ui/README.md).

## Interoperability targets (not dependencies)

Marathon Shepherd is designed to feed devices to, and integrate with, the
following projects. It does not bundle or redistribute any of them:

- [MarathonLabs/marathon](https://github.com/MarathonLabs/marathon) — Apache-2.0
- [Android Cuttlefish](https://github.com/google/android-cuttlefish) — Apache-2.0
- [Jenkins](https://www.jenkins.io/) — MIT

See [NOTICE](NOTICE) for the trademark and non-affiliation statement.
