# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] — 2026-09-10

First public release. Everything below describes the state at the point the repository
was opened up, not a diff against a previous public version.

### Added

- Manager service with a REST API for session-scoped Android device allocation across
  physical racks, emulator farms and Cuttlefish hosts.
- Three adapters behind one contract: `shepherd-adb`, `shepherd-farm`,
  `shepherd-cuttlefish`.
- `mshctl` CLI.
- Jenkins shared library `runMarathonWithShepherd`, which allocates devices, waits for
  ADB readiness and injects `adbServers` into Marathon through a generated Gradle init
  script.
- Layered test suite (unit, component, integration, e2e, scale) behind a single runner
  with per-stage selection, plus a `Jenkinsfile` and GitHub Actions CI.
- Container images for the manager and all three adapters, published to GHCR for
  `linux/amd64` and `linux/arm64` by a tag-triggered release workflow.

### Fixed

Issues found in the pre-publication audit:

- `./gradlew` could not bootstrap from a fresh clone: `gradle-wrapper.jar` was excluded
  by a blanket `*.jar` rule in `.gitignore` and had never been committed. The wrapper is
  now tracked and the distribution is checksum-verified.
- `GET /api/v1/config` returned every provider's bearer secret in plaintext over the
  unauthenticated Manager API. Secrets are now redacted, and a `PUT` echoing the
  placeholder preserves the stored value.
- The Cuttlefish adapter disabled TLS certificate validation by default — and did so
  precisely when the orchestrator URL used `https://`. Validation is now on by default,
  in the code and in the published image.
- A blank `ADAPTER_SECRET` was documented as disabling authentication but instead left
  protected adapter routes unusable.
- Adapter bearer tokens were compared with ordinary string equality; the comparison is
  now constant time.
- `runCommand` could never time out: stdout was drained to EOF before `waitFor`, so a
  wedged `adb` blocked the calling thread indefinitely.
- The test runner required `adb`, Groovy and Docker on every invocation, so
  `--only unit:kotlin` was unrunnable on a clean machine or a hosted CI runner.
- `pre-commit-check` invoked `scripts/run_tests.sh`, a path that no longer existed.
- Releasing an ADB lease closed only the listening socket, so a connection that was
  already open kept full device access after the lease ended — and the port went back
  into the pool while that connection was still live.

### Changed

- Sample APKs (~58 MB of third-party build output) are no longer committed; build them
  with `scripts/build_test_apk.sh`.
- Gradle toolchain pinned to JDK 21 for every module.
- ktlint added, with configuration in `.editorconfig`.
- `SessionManager` reduced to session lifecycle; provider-matching policy moved to
  `domain/allocation/ProviderMatcher` and the "no matching devices" diagnostic to
  `domain/allocation/NoMatchingDevicesReport`.
- `CloudOrchestratorService` reduced to orchestration; its HTTP/TLS stack moved to
  `CloudOrchestratorTransport` and the wire model plus JSON dialect-sniffing to
  `CloudOrchestratorWire`.
- `tests/run_tests.py` split into the `tests/msh_runner` package. The 1,200-line
  `TestRunner` is now composed of stage-state, preflight, execution and reporting mixins;
  the command-line interface is unchanged.
- Docker builder stages run on the build host's native platform, so multi-arch images
  never compile Kotlin under emulation.

[Unreleased]: https://github.com/rus-artur4ik/marathon-shepherd/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rus-artur4ik/marathon-shepherd/releases/tag/v0.1.0
