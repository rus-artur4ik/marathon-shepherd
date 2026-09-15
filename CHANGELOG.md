# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **The first sign-in creates an account instead of only a password.** Signing in with the
  one-time password no longer asks for it a second time — that password is what opened the
  session. The account the manager creates at first start now belongs to nobody until that
  screen is filled in: its first administrator picks the name they will sign in with and a
  display name, and `admin` stops existing unless they keep it. Someone whose password an admin
  reset still just chooses a new password, under their own name. New endpoint:
  `POST /api/v1/me/setup`; `GET /api/v1/me` and the sign-in response carry `unclaimed`.

### Fixed

- The Kubernetes docs pointed `ADB_SERVER_SOCKET` at `127.0.0.1`, where adb refuses to start a
  server of its own, so shepherd-adb came up without one. They say `tcp:localhost:5038` now.

## [0.2.0] — 2026-09-14

Marathon Shepherd grows from a CI device broker into a device manager: authenticated access
for many clients, a control plane over individual devices, client libraries, an MCP server so
AI agents can lease devices, and the operational pieces to run it as a service.

### Breaking

- **The Manager API requires authentication.** Every `/api/v1/...` call needs an API key
  (`Authorization: Bearer <key>`) or a signed-in browser session, and `/mcp` needs a key;
  `/live`, `/ready`, `/health`, `/metrics` and the API docs stay public. On first start the
  manager creates the user `admin` with a one-time password, prints it once and writes it to
  `<dataDir>/initial-admin-password`; `MSH_ADMIN_PASSWORD` chooses it instead, and
  `MSH_ADMIN_TOKEN` provisions a static admin API key. Existing callers need a key of their
  own — `mshctl clients create --name ci --role user`.
- Sessions belong to the client or person that created them: only they or an admin may wait on,
  extend or release one.
- `mshctl create --api` no longer defaults to `34`. Without it, any API level matches.
- The Jenkins step takes `credentialsId` (or `MSH_TOKEN`) and reads the key inside the shell,
  so it never reaches the build log.

### Added

- **Web UI** at `/ui/`, where the manager's address now leads: sign-in with a password or a button per
  OIDC provider, an overview, devices (take one, maintenance), sessions (create, extend, release,
  `adb` commands), providers, live activity, and for admins users, API clients, the audit log and
  the configuration. Plain HTML, CSS and JavaScript modules served by the manager under a strict
  Content-Security-Policy, with no build step.
- **Signing in.** Local accounts with passwords, LDAP / Active Directory and any number of OIDC
  providers, with directory groups mapped to roles. Browser sessions use an `HttpOnly` cookie
  with a CSRF token; failed sign-ins lock a username out for a while. People get personal API
  tokens (`mshctl login`), and admins manage them with `/api/v1/admin/users` and `mshctl users`.
  See `docs/authentication.md`.
- **Identity and quotas.** Named clients with roles (`admin`, `user`, `viewer`, `provider`),
  per-client quotas for concurrent devices, session lifetime and queue priority, an audit log
  at `GET /api/v1/audit`, `GET /api/v1/me`, and an admin API to create, update, rotate and
  revoke keys.
- **Device-level control plane.** `GET /api/v1/devices` lists every device with its state,
  holder and labels; `GET /api/v1/devices/{id}` shows one; admins can put a device into
  maintenance. Sessions can ask for specific device ids or labels, carry a name, metadata, a
  priority and an idle timeout, and report the devices they hold.
- `POST /api/v1/sessions/{id}/heartbeat` and `/extend`; READY sessions with an idle timeout are
  released when nobody checks in.
- **Adapter self-registration.** An adapter with a `provider` key registers itself and
  heartbeats (`MSH_MANAGER_URL`, `MSH_REGISTRATION_TOKEN`, `ADAPTER_PUBLIC_URL`);
  `GET/DELETE /api/v1/providers` lists and removes registrations.
- **Events.** `GET /api/v1/events` streams session, device, provider and lease events as
  server-sent events, with `Last-Event-ID` replay.
- **Observability.** Prometheus metrics on the manager and every adapter, a background fleet
  poller behind `/health` and the device list, a `/ready` probe, an OpenAPI document at
  `/openapi.yaml` with Swagger UI at `/docs`, and a Grafana dashboard plus alert rules in
  `deploy/observability`.
- **Clients.** `manager/client` (Kotlin) with queue-aware `acquire`/`withSession` helpers and a
  reconnecting event flow; `clients/python`, a dependency-free client for Python 3.9+ with a
  session context manager and a pytest fixture; `mshctl` rebuilt on the Kotlin client with
  commands for devices, events, providers, clients, audit and config.
- **MCP server for agents.** `/mcp` (stateless Streamable HTTP) inside the manager and a
  `shepherd-mcp` stdio binary, both offering list/acquire/wait/get/extend/release tools with
  guardrails: at most two devices per session, short lifetimes and an idle timeout, and
  release-on-exit for the stdio server. See `docs/mcp.md`.
- Scheduler policies: strict FIFO (default) or priority order, set with `scheduler.policy`.
- Orphaned adapter leases are reclaimed by a two-pass reconciliation.
- **Kubernetes.** A `shepherd-adapter` chart for adapters that run on a node (host network, USB
  devices, persistent lease state), a `hostNetwork` option in the manager chart, and delivery from
  the Jenkinsfile: on master it pushes `:edge` and `:sha-<commit>` images and upgrades the installed
  Helm releases. See `docs/kubernetes.md`.
- **Operations.** Optional Postgres with `MSH_DB_URL` (SQLite stays the default), a
  single-manager lock on the database, retention for finished sessions and audit entries, and
  a Helm chart in `deploy/helm`.
- Adapters report individual devices, accept device selection and labels
  (`ADB_DEVICE_LABELS_FILE`), and support lease renewal and listing.

### Changed

- Kotlin 2.4 and Ktor 3.5. Wire types moved into `adapter/contract` and `manager/protocol`, so
  the service, clients, CLI and MCP server share one definition of the API.
- `/health` and `/api/v1/devices` answer from a background snapshot instead of fanning out to
  every adapter per request; `?refresh=true` still polls.
- The manager and adapters report the version from the build instead of a constant.
- The manager image also carries `mshctl` and `shepherd-mcp`.

### Fixed

- A physical rack whose devices were all busy answered `503` instead of queueing the session:
  providers that list devices now count busy ones as registered.
- Error bodies keep their `{"error": ...}` shape on routes that negotiate another format.

### Security

- API keys are stored only as SHA-256 digests and shown once; see the rewritten
  [SECURITY.md](SECURITY.md) for the threat model, including what stays public and why TLS
  belongs in front of the manager.

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
  with per-stage selection, plus a `Jenkinsfile` and GitHub Actions CI; the Docker-backed
  tiers run as an on-demand workflow.
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

[Unreleased]: https://github.com/rus-artur4ik/marathon-shepherd/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/rus-artur4ik/marathon-shepherd/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/rus-artur4ik/marathon-shepherd/releases/tag/v0.1.0
