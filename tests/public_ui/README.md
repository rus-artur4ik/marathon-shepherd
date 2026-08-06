# Public UI test fixtures

The device-backed test tiers (`e2e:apk` and `public-ui`) install a real Android
app and run its instrumentation suite through Shepherd's ADB proxy. That app is
[android/architecture-samples](https://github.com/android/architecture-samples),
built from a pinned upstream commit.

## The APKs are not committed

`apks/` is git-ignored. The two APKs total ~58 MB, they are deterministic build
output of a third-party project, and committing them would put third-party
binaries with no attribution into every clone of this repository.

Build them once — they are cached on disk and reused by every later run:

```bash
./scripts/build_test_apk.sh
```

The script clones `android/architecture-samples` at the pinned commit into an
ephemeral sandbox (`.msh-sandbox/`, also git-ignored), pins the AndroidX test
libraries to the versions listed below, builds the debug and androidTest APKs,
copies just those two artifacts into `apks/`, and deletes the sandbox.

Rebuild from scratch with:

```bash
./scripts/build_test_apk.sh --refresh
```

## What gets produced

```
apks/architecture-samples-<upstream-sha>-androidx-test-<espresso-version>/
├── app-debug.apk               # the sample app
├── app-debug-androidTest.apk   # its instrumentation suite
└── metadata.env                # provenance: upstream URL, commit, versions
```

The pinned coordinates live at the top of
[`scripts/build_test_apk.sh`](../../scripts/build_test_apk.sh) — that file is
the single source of truth. Changing the pin changes the directory name, so
stale builds never get picked up silently.

## Requirements

The build needs a JDK and the Android SDK. `build_test_apk.sh` provisions the
SDK into the sandbox itself, so the only hard prerequisites are `git`, a JDK,
and network access to Google's Maven repository.

## Licensing

`android/architecture-samples` is licensed under Apache-2.0, and
`build_test_apk.sh` modifies its AndroidX test dependency versions before
building. Because the resulting APKs are produced locally and never
redistributed by this repository, no attribution ships with them. If you
redistribute these APKs yourself, you are redistributing a modified Apache-2.0
work and must comply with its terms. See
[THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md).
