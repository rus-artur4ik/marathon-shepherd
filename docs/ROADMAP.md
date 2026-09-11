# Roadmap: device manager control plane

Working checklist for turning Marathon Shepherd from a CI device broker into a
multi-client device manager with its own control plane, observability, client
libraries and an MCP server for bots. Items are ticked as they land on the
`feat/device-manager` branch (one commit per phase).

## Decisions

- **MCP** uses the official Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk` 0.15.0).
  That requires Kotlin 2.4 and Ktor 3.5, so the whole build is upgraded first.
- **Authentication is on by default.** On first start the manager creates an admin
  API key, prints it once and stores it in `<dataDir>/initial-admin-token`
  (`MSH_ADMIN_TOKEN` provisions a known admin token instead). Clients without a token
  get `401`.
- The embedded MCP endpoint is **stateless Streamable HTTP** behind the same bearer
  auth as the REST API; a separate `shepherd-mcp` binary serves stdio for local agents.
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

- [ ] `clients` table; API keys stored as SHA-256 hashes; roles `admin`, `user`,
      `viewer`, `provider`
- [ ] Bearer auth on `/api/v1/*` and `/mcp`; probes, metrics and API docs stay public
- [ ] Bootstrap admin: `MSH_ADMIN_TOKEN` or a generated key printed once
- [ ] Session ownership; only the owner or an admin can release, wait on or extend
- [ ] Per-client quotas: concurrent devices, session lifetime, priority ceiling
- [ ] Audit log with `GET /api/v1/audit` and retention
- [ ] Admin API for clients (create, list, rotate, revoke) and `GET /api/v1/me`
- [ ] Jenkins library, CLI, scale and integration harnesses send the token

## Phase 3 — Device-level control plane

- [ ] Adapter contract: per-device list in `/status`; `deviceIds`, `excludeDeviceIds`
      and `labels` in `/acquire`; leased device ids in the response; lease renew
- [ ] shepherd-adb reports every device (including offline/unauthorized), honours
      device selection and serial labels; cuttlefish reports running instances
- [ ] Manager device view: `GET /api/v1/devices` flat list, `GET /api/v1/devices/{id}`
- [ ] Sessions record which devices they hold; responses list them
- [ ] Maintenance mode for devices (admin), excluded from allocation
- [ ] Session create accepts device ids, labels, name, metadata, priority and
      idle timeout
- [ ] `POST /sessions/{id}/heartbeat` and `/extend`; idle READY sessions are reclaimed
- [ ] Adapter self-registration with heartbeat (`provider` role keys) alongside
      static `msh.yaml` providers; `GET/DELETE /api/v1/providers`
- [ ] `GET /api/v1/events` server-sent event stream with `Last-Event-ID` replay
- [ ] Scheduler policies: strict FIFO (default) and priority

## Phase 4 — Client layer

- [ ] `manager/client`: Kotlin client library implementing the shared `ShepherdApi`
- [ ] `mshctl` rebuilt on the client; new commands for the new API surface
- [ ] `clients/python`: dependency-free Python client with a session context manager
- [ ] "Connect your client" documentation

## Phase 5 — MCP server

- [ ] `manager/mcp`: tools (list/acquire/wait/get/extend/release devices and
      sessions) and resources over `ShepherdApi`
- [ ] Embedded stateless Streamable HTTP endpoint at `/mcp`, bound to the caller's key
- [ ] `shepherd-mcp` stdio binary that talks to the manager over HTTP
- [ ] Guardrails for bots: device cap, short default TTL, idle timeout,
      release-on-exit for stdio
- [ ] Protocol-level tests and setup guide for Claude Code and other MCP clients

## Phase 6 — Operations

- [ ] Optional Postgres (`MSH_DB_URL`) with a pooled connection; SQLite stays default
- [ ] Single-instance lock (advisory lock on Postgres, file lock on SQLite)
- [ ] Retention for finished sessions and audit records
- [ ] Helm chart in `deploy/helm`
- [ ] Compose files, Dockerfiles and test harnesses updated for auth
- [ ] README, SECURITY.md, CHANGELOG and version bump to 0.2.0
