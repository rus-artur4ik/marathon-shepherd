# Roadmap: device manager control plane

Working checklist for turning Marathon Shepherd from a CI device broker into a
multi-client device manager with its own control plane, observability, client
libraries and an MCP server for bots. Items are ticked as they land on the
`feat/device-manager` branch (one commit per phase).

## Decisions

- **MCP** uses the official Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk` 0.15.0).
  That requires Kotlin 2.4 and Ktor 3.5, so the whole build is upgraded first.
- **Authentication is on by default.** On first start the manager creates the user
  `admin` with a one-time password, prints it once and stores it in
  `<dataDir>/initial-admin-password` (`MSH_ADMIN_PASSWORD` chooses it, `MSH_ADMIN_TOKEN`
  provisions a static admin key). Phase 2 generated an admin API key; Phase 7 replaced it
  with the password. Callers without credentials get `401`.
- The embedded MCP endpoint is **stateless Streamable HTTP** behind the same bearer
  auth as the REST API; a separate `shepherd-mcp` binary serves stdio for local agents.
- People sign in with a **username and password** by default; **OIDC** providers (any
  standard issuer) and **LDAP / Active Directory** can be added in `msh.yaml`, with groups
  mapped to roles. API keys stay for CI jobs, scripts and agents.
- The **web UI** is plain HTML, CSS and JavaScript modules served by the manager, with no
  frontend build step. It is a client of the same JSON API, authenticated by a server-side
  session cookie with CSRF protection.
- One manager instance per database. Postgres is supported for durability, not for
  running replicas side by side; a startup lock enforces this.

## Phase 0 — Platform

- [x] Kotlin 2.2.21 → 2.4.20, Ktor 2.3.13 → 3.5.2, kotlinx.serialization 1.11,
      coroutines 1.11, kaml 0.104, Clikt 5.1
- [x] Ktor 3 API migration (CallLogging package, routing context)
- [x] `adapter/contract`: adapter wire DTOs split out of the adapter runtime
- [x] `manager/protocol`: manager API DTOs shared by service, client, CLI and MCP
- [x] Manager and adapter version taken from the build instead of a constant
- [x] Existing test suite green

## Phase 1 — Observability

- [x] `/metrics` (Prometheus) on the manager: HTTP, JVM and domain metrics
- [x] Domain metrics: sessions by status, queue depth, queue wait histogram,
      allocation outcomes, devices per provider and state, provider up/down,
      adapter call latency, build info
- [x] Background provider poller; `/health` and `/api/v1/devices` read a snapshot
      instead of fanning out to every adapter per request
- [x] `/ready` probe (database + config); `/live` unchanged
- [x] `/metrics` on every adapter through the shared adapter runtime
- [x] OpenAPI spec at `/openapi.yaml`, Swagger UI at `/docs`, and a test that keeps
      routes and spec in sync
- [x] Grafana dashboard and Prometheus alert rules in `deploy/observability`

## Phase 2 — Identity and multi-client access

- [x] `clients` table; API keys stored as SHA-256 hashes; roles `admin`, `user`,
      `viewer`, `provider`
- [x] Bearer auth on `/api/v1/*` and `/mcp`; probes, metrics and API docs stay public
- [x] Bootstrap admin: `MSH_ADMIN_TOKEN` or a generated key printed once
- [x] Session ownership; only the owner or an admin can release, wait on or extend
- [x] Per-client quotas: concurrent devices, session lifetime, priority ceiling
- [x] Audit log with `GET /api/v1/audit` and retention
- [x] Admin API for clients (create, list, rotate, revoke) and `GET /api/v1/me`
- [x] Jenkins library, CLI, scale and integration harnesses send the token

## Phase 3 — Device-level control plane

- [x] Adapter contract: per-device list in `/status`; `deviceIds`, `excludeDeviceIds`
      and `labels` in `/acquire`; leased device ids in the response; lease renew
- [x] shepherd-adb reports every device (including offline/unauthorized), honours
      device selection and serial labels; cuttlefish reports running instances
- [x] Manager device view: `GET /api/v1/devices` flat list, `GET /api/v1/devices/{id}`
- [x] Sessions record which devices they hold; responses list them
- [x] Maintenance mode for devices (admin), excluded from allocation
- [x] Session create accepts device ids, labels, name, metadata, priority and
      idle timeout
- [x] `POST /sessions/{id}/heartbeat` and `/extend`; idle READY sessions are reclaimed
- [x] Adapter self-registration with heartbeat (`provider` role keys) alongside
      static `msh.yaml` providers; `GET/DELETE /api/v1/providers`
- [x] `GET /api/v1/events` server-sent event stream with `Last-Event-ID` replay
- [x] Scheduler policies: strict FIFO (default) and priority
- [x] Orphaned adapter leases reclaimed by a two-pass reconciliation (`lease-list` adapters)
- [x] Fix: a physical rack with every device busy queued nothing and answered 503

## Phase 4 — Client layer

- [x] `manager/client`: Kotlin client library implementing the shared `ShepherdApi`
- [x] `mshctl` rebuilt on the client; new commands for the new API surface
- [x] `clients/python`: dependency-free Python client with a session context manager
- [x] "Connect your client" documentation (`docs/clients.md`)

## Phase 5 — MCP server

- [x] `manager/mcp`: tools (list/acquire/wait/get/extend/release devices and
      sessions) and resources over `ShepherdApi`
- [x] Embedded stateless Streamable HTTP endpoint at `/mcp`, bound to the caller's key
- [x] `shepherd-mcp` stdio binary that talks to the manager over HTTP
- [x] Guardrails for bots: device cap, short default TTL, idle timeout,
      release-on-exit for stdio
- [x] Protocol-level tests and setup guide for Claude Code and other MCP clients

## Phase 6 — Operations

- [x] Optional Postgres (`MSH_DB_URL`) with a pooled connection; SQLite stays default
- [x] Single-instance lock (advisory lock on Postgres, file lock on SQLite)
- [x] Retention for finished sessions and audit records
- [x] Helm chart in `deploy/helm`
- [x] Compose files, Dockerfiles and test harnesses updated for auth
- [x] README, SECURITY.md, CHANGELOG and version bump to 0.2.0

## Phase 7 — Human sign-in

- [x] Users with local passwords (PBKDF2), separate from API clients; the first start creates
      an `admin` user with a one-time password that must be changed
- [x] Browser sessions: server-side session cookie, CSRF token, login lockout, security headers
- [x] Personal API tokens for users; `mshctl login`
- [x] LDAP / Active Directory sign-in with group → role mapping
- [x] OIDC sign-in (authorization code + PKCE) for any number of providers, group → role mapping
- [x] Admin API for users; audit entries for sign-in, sign-out and account changes
- [x] Tests against an in-memory LDAP server and a fake OIDC provider; docs and OpenAPI

## Phase 8 — Web UI

- [x] Sign-in page: password form and one button per OIDC provider
- [x] Overview, devices (with maintenance), sessions (create, extend, release), providers
- [x] Live event feed
- [x] Administration: users, API clients and keys, audit log, configuration
- [x] Profile: change password, personal API tokens
- [x] Served by the manager with a strict Content-Security-Policy; checked in a real browser

## Phase 9 — Kubernetes delivery

- [x] `shepherd-adapter` chart: host network, USB devices, persistent lease state
- [x] `hostNetwork` option in the manager chart
- [x] Jenkinsfile delivery from master: images pushed to a registry, installed Helm releases upgraded
- [x] Shared-library tests run in a container on agents without Groovy
- [x] docs/kubernetes.md
