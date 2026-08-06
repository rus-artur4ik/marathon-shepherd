# Contributing to Marathon Shepherd

Thanks for taking the time. This document covers what you need installed, how to run the
right slice of the test suite, and what a reviewable change looks like here.

## Prerequisites

The only hard requirements for building and running the fast tests are:

| Tool | Version | Why |
| --- | --- | --- |
| JDK | **21** | Pinned via the Gradle toolchain; all runtime images are `eclipse-temurin:21-jre` |
| `curl` | any | Used by the test runner and the Jenkins shared library |
| `python3` | 3.9+ | The test runner (`tests/run_tests.py`) |

Everything else is needed only by specific test tiers, and the runner asks for it only
when you select those tiers:

| Tool | Needed by |
| --- | --- |
| Docker | `component:docker`, `integration:docker-compose`, `e2e:docker-scenarios`, `scale`, `component-cli` |
| `adb` (Android platform-tools) | `e2e:real-device-session`, `e2e:apk-instrumentation`, `component-cli` |
| Apache Groovy | `unit:jenkins` |
| `k6` (optional) | `scale`, only with `--k6`; otherwise a bundled Python driver is used |

Do **not** install Gradle — use the wrapper (`./gradlew`). It is checked in, and
`gradle-wrapper.properties` pins a `distributionSha256Sum`, so the distribution is
verified on download.

## Building

```bash
./gradlew build -x test
```

## Running tests

The single entry point is `tests/run_tests.sh` (a thin wrapper around
`tests/run_tests.py`; the runner itself is the `tests/msh_runner/`
package). List what exists:

```bash
./tests/run_tests.sh --list-ids
```

**The fast gate** — no Docker, no device, no Groovy. Run this before every push:

```bash
./tests/run_tests.sh --only unit:kotlin
```

**Everything that does not need hardware:**

```bash
./tests/run_tests.sh --skip-real-device --skip-apk-e2e
```

**Just the Gradle tests**, if you prefer to bypass the runner:

```bash
./gradlew test
```

**Jenkins shared-library tests** (needs Groovy):

```bash
groovy tests/unit/jenkins_unit_test.groovy
```

You can wire the fast gate into a git hook:

```bash
ln -s ../../pre-commit-check .git/hooks/pre-commit
```

### Device- and Docker-backed tiers

`e2e:apk-instrumentation` and `public-ui` install a real Android app. The sample APKs are
**not committed** — build them once:

```bash
./scripts/build_test_apk.sh
```

See [tests/public_ui/README.md](tests/public_ui/README.md) for details.

Cuttlefish tiers need a Linux host with nested virtualisation; they cannot run on macOS
or on hosted GitHub runners.

## Code style

Kotlin is checked with [ktlint](https://pinterest.github.io/ktlint/):

```bash
./gradlew ktlintCheck     # verify
./gradlew ktlintFormat    # auto-fix
```

The configuration lives in [`.editorconfig`](.editorconfig). It deliberately uses the
`intellij_idea` code style rather than `ktlint_official`, and disables a few purely
cosmetic rules — each with a comment explaining why. If a rule genuinely gets in the way,
change it there rather than sprinkling suppressions.

Shell scripts should pass `shellcheck --severity=error` and start with
`set -euo pipefail`.

## Commit and PR conventions

- One logical change per commit; write the message in the imperative mood
  (`Fix port leasing deadlock`, not `Fixed...`).
- Explain **why** in the body when the change is not self-evident.
- PRs should describe the behaviour change and say which test tiers you ran. CI runs the
  fast tiers on every PR and push to `master`. The Docker-backed tiers are a separate,
  manually triggered workflow (`Docker tiers`); the header of
  `.github/workflows/docker-tiers.yml` explains why two of them fail on hosted runners.
- New behaviour needs a test. Bug fixes should come with a regression test that fails
  without the fix — several existing tests are written that way, and they are the ones
  that keep catching things.

## Reporting security issues

Please do **not** open a public issue for a vulnerability. See
[SECURITY.md](SECURITY.md).

## Licence of contributions

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the same licence as the project. There is no CLA.
