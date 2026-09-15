# Marathon Shepherd

> One REST API that hands out Android test devices — physical racks, emulator farms and
> Cuttlefish hosts alike — to any CI job, so your pipeline asks for "2 devices on API 34"
> instead of hard-coding which machine has them.

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![JVM 21](https://img.shields.io/badge/jvm-21-437291)](https://adoptium.net/)
[![Kotlin 2.4](https://img.shields.io/badge/kotlin-2.4-7F52FF)](https://kotlinlang.org/)
[![CI](https://github.com/rus-artur4ik/marathon-shepherd/actions/workflows/ci.yml/badge.svg)](https://github.com/rus-artur4ik/marathon-shepherd/actions/workflows/ci.yml)

Android test devices are usually pinned to whichever machine happens to host them, so CI
jobs end up referencing specific adb hosts and fighting each other for hardware.
Marathon Shepherd puts a scheduler in front of the fleet: a job requests a *number* of
devices matching a *capability* (API level, physical vs emulator), gets a lease with adb
endpoints it can talk to, and returns them when it is done. Sessions are queued when the
pool is busy, and released automatically on TTL expiry so a crashed job cannot strand a
device.

It is built for [Marathon](https://marathonlabs.github.io/marathon/) — the shipped Jenkins
step injects the leased adb servers straight into Marathon's Gradle configuration — but the
REST API is runner-agnostic.

People sign in with a password, LDAP / Active Directory or an OIDC provider; CI jobs, scripts
and AI agents use API keys. Every caller has a role and a quota, so they share one fleet without
stepping on each other: sessions are owned, and everything that happens is in an audit log and an
event stream.

> **Not affiliated with Marathon Labs.** "Marathon" is used descriptively to name the test
> runner this project feeds devices to. See [NOTICE](NOTICE).

> **Status: early.** Version 0.2.0; container images are published to GHCR.
> The HTTP API and config schema may still change. See [CHANGELOG.md](CHANGELOG.md) —
> 0.2.0 makes API keys mandatory, so callers written against 0.1.0 need one.

## Contents

- [How It Works](#how-it-works)
- [Quick Start](#quick-start)
- [Manager API](#manager-api)
- [Web UI](#web-ui)
- [Signing in](#signing-in)
- [Clients](#clients)
- [Devices for AI agents](#devices-for-ai-agents)
- [Configuration](#configuration)
- [Observability](#observability)
- [Jenkins](#jenkins)
- [Testing](#testing)
- [Project Structure](#project-structure)
- [Security](#security)
- [Contributing](#contributing)
- [Support](#support)
- [License](#license)

## How It Works

```
 ┌───────────────────────────────────────────┐
 │  CI / Jenkins / developer machine         │
 │                                           │
 │  mshctl · Jenkins · Python · AI agent     │
 └──────────────┬────────────────────────────┘
                │ HTTP :6037
                ▼
 ┌───────────────────────────────────────────┐
 │  Manager host                             │
 │                                           │
 │  shepherd-manager :6037                   │
 │  └── SQLite  (session state, leases)      │
 └──────┬───────────────┬──────────────┬─────┘
        │ HTTP :7037    │ HTTP :7037   │ HTTP :7037
        ▼               ▼              ▼
 ┌─────────────┐  ┌───────────────┐  ┌──────────────────────┐
 │ Device host │  │ Emulator host │  │  Cuttlefish host     │
 │             │  │               │  │                      │
 │ shepherd-adb│  │ shepherd-farm │  │  shepherd-cuttlefish │
 │   :7037     │  │   :7037       │  │    :7037             │
 │             │  │               │  │                      │
 │ adb-server  │  │ farm-server   │  │  cloud-orchestrator  │
 │   :5037     │  │   :5037       │  │  instances :6520     │
 │ USB devices │  │  emulators    │  │  (KVM required)      │
 └──────┬──────┘  └──────┬────────┘  └──────────┬───────────┘
        │ ADB :5037      │ ADB :5037            │ ADB :6520
        └────────────────┴──────────────────────┘
              marathon runner connects here
```

**Session lifecycle:** `create → PENDING → acquire leases from providers → READY`. On release or TTL expiry, all leases are rolled back automatically. The manager returns `adbServers` — for `shepherd-adb` these are lease-scoped proxy ports, not a shared rack-wide adb daemon.

## Prerequisites

- JDK 21 (shell quick start)
- Docker + Compose (Docker quick start)

---

## Quick Start — Shell

Build and run everything locally without Docker.

**1. Create `msh.yaml`** in the directory you'll run the manager from:

```yaml
providers:
  - name: "device-rack-1"
    url: "http://192.168.1.10:7037"
    # Optional only when direct ADB access must use another host
    # accessHost: "192.168.1.11"
    secret: "change-me"   # must match ADAPTER_SECRET on that host
```

**2. Start the manager**

```bash
./gradlew :manager:service:installDist
./manager/service/build/install/manager/bin/manager
```

Manager looks for `msh.yaml` in the current directory by default. Pass `--config /path/to/msh.yaml` to override.

**3. Install the CLI**

```bash
./gradlew :manager:cli:installDist
sudo ln -sf "$PWD/manager/cli/build/install/mshctl/bin/mshctl" /usr/local/bin/mshctl
```

**4. Sign in as the first admin**

On first start the manager creates an account named `admin` and prints a one-time password (a
copy is in `~/.msh/initial-admin-password`). Sign in with it at `http://localhost:6037/`: the
first sign-in asks for the name you want to sign in with from then on and a password of your own.
From the shell it goes like this instead, keeping the name `admin`:

```bash
export MSH_URL=http://localhost:6037
mshctl passwd --username admin                       # replace the one-time password
mshctl login --username admin                        # keeps a personal token for mshctl
mshctl users create --username dana --role user      # people who sign in
mshctl clients create --name nightly-ci --role user  # keys for CI jobs and agents
```

Set `MSH_ADMIN_PASSWORD` before the first start to choose the first password yourself, or
`MSH_ADMIN_TOKEN` to provision a static admin API key for automation.

**5. Verify and create a session**

```bash
mshctl health
mshctl devices
mshctl create --devices 2 --api 34 --wait
mshctl list --mine
mshctl release sess_abc123
```

---

## Quick Start — Docker

> Images are published to the GitHub Container Registry for `linux/amd64` and
> `linux/arm64`: `ghcr.io/rus-artur4ik/marathon-shepherd`, `shepherd-adb`, `shepherd-farm`
> and `shepherd-cuttlefish`, each tagged with the release version (`0.2.0`), its minor line
> (`0.2`) and `latest`. The examples pin `0.2.0`.
> `deploy/Dockerfile.adb-server` is not published (it is a modified Ubuntu image — see
> [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)), so the adb-server service builds from a clone.

### Manager

**1. Create `msh.yaml`:**

```yaml
providers:
  - name: "device-rack-1"
    url: "http://192.168.1.10:7037"
    # Optional only when direct ADB access must use another host
    # accessHost: "192.168.1.11"
    secret: "change-me"   # must match ADAPTER_SECRET on that host
```

**2. Create `docker-compose.yml`:**

```yaml
services:
  manager:
    image: ghcr.io/rus-artur4ik/marathon-shepherd:0.2.0
    container_name: shepherd-manager
    restart: unless-stopped
    environment:
      # Optional. Without it the manager gives the user `admin` a one-time password on first
      # start, prints it once in the container log and keeps a copy in
      # /var/lib/msh/initial-admin-password.
      MSH_ADMIN_PASSWORD: "${MSH_ADMIN_PASSWORD:-}"
    volumes:
      - "./msh.yaml:/etc/msh/msh.yaml:ro"
      - msh-data:/var/lib/msh
    ports:
      - "6037:6037"
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:6037/live"]
      interval: 30s
      timeout: 5s
      retries: 3

volumes:
  msh-data:
```

**3. Start:**

```bash
docker compose up -d
curl -sf http://localhost:6037/live
docker compose logs manager | grep -A4 "first admin account"   # the one-time password, printed once
docker exec shepherd-manager mshctl --manager http://localhost:6037 health
```

Reload config at runtime without restart:

```bash
curl -X POST http://localhost:6037/api/v1/config/reload
```

### Adapters (one per device host)

Each adapter runs on its own host alongside the devices it manages. Pick the right type for your hardware.

**Physical devices (shepherd-adb)**

Manager uses the provider `url` host from `msh.yaml` as the default adb host. No adapter-side host override is required. `shepherd-adb` allocates per-device leases and exposes each leased physical device through its own session-scoped adb proxy port.

**1. Create `docker-compose.yml` on the device host:**

```yaml
networks:
  shepherd:
    driver: bridge

volumes:
  shepherd-adb-state:

services:
  adb:
    build:
      context: .
      dockerfile: deploy/Dockerfile.adb-server
    image: msh-adb-server:latest
    container_name: adb-server
    privileged: true
    devices:
      - /dev/bus/usb
    networks:
      - shepherd
    restart: unless-stopped
    ports:
      - "5038:5037"

  shepherd-adb:
    image: ghcr.io/rus-artur4ik/shepherd-adb:0.2.0
    container_name: shepherd-adb
    networks:
      - shepherd
    restart: unless-stopped
    environment:
      ADAPTER_ADB_PORT: "5038"          # upstream adb daemon published on the host
      ADB_PROXY_PORT_RANGE: "7600-7699" # lease-scoped adb proxy ports published to Marathon / CI
      ADB_LEASES_PATH: "/var/lib/msh/adb-leases.json"
      ADAPTER_SECRET: "change-me"        # must match secret in msh.yaml
    volumes:
      - shepherd-adb-state:/var/lib/msh
    ports:
      - "7037:7037"
      - "7600-7699:7600-7699"
    depends_on:
      - adb
```

**2. Start:**

```bash
docker compose up -d
curl -sf http://192.168.1.10:7037/health
```

---

**Emulator farm (shepherd-farm)**

By default, manager publishes the host from `provider.url`. Use `accessHost` in `msh.yaml` only when direct ADB access lives on another host than the adapter itself.

For `shepherd-adb`, Marathon never talks to the shared upstream adb daemon directly. Each allocated physical device gets its own lease-scoped proxy port from `ADB_PROXY_PORT_RANGE`, and the session response contains those ports in `adbServers`.

**1. Create `docker-compose.yml` on the emulator host:**

```yaml
networks:
  shepherd:
    driver: bridge

services:
  farm-server:
    image: YOUR_USER/farm-server:latest
    container_name: farm-server
    networks:
      - shepherd
    restart: unless-stopped
    ports:
      - "5038:5037"
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/health"]
      interval: 30s
      retries: 5
      start_period: 60s

  shepherd-farm:
    image: ghcr.io/rus-artur4ik/shepherd-farm:0.2.0
    container_name: shepherd-farm
    networks:
      - shepherd
    restart: unless-stopped
    environment:
      FARM_SERVER_HOST: "farm-server"
      ADAPTER_ADB_PORT: "5038"   # published host adb port for CI / Marathon
      ADAPTER_SECRET: "change-me"
    ports:
      - "7037:7037"
    depends_on:
      farm-server:
        condition: service_healthy
```

**2. Start:**

```bash
docker compose up -d
```

---

**Cuttlefish (shepherd-cuttlefish)**

By default, manager publishes the host from `provider.url`. Use `accessHost` in `msh.yaml` only when direct device access lives on another host.

**1. Create `docker-compose.yml` on the Cuttlefish host:**

```yaml
services:
  shepherd-cuttlefish:
    image: ghcr.io/rus-artur4ik/shepherd-cuttlefish:0.2.0
    container_name: shepherd-cuttlefish
    privileged: true
    devices:
      - /dev/kvm
    extra_hosts:
      - "host.docker.internal:host-gateway"
    volumes:
      - cuttlefish-home:/home/vsoc-01
    restart: unless-stopped
    environment:
      ADAPTER_ADB_PORT: "6520"
      ADAPTER_SECRET: "change-me"
      CUTTLEFISH_ORCHESTRATOR_URL: "https://host.docker.internal:2443"
      CUTTLEFISH_ORCHESTRATOR_INSECURE_TLS: "true"
      CUTTLEFISH_ORCHESTRATOR_LEASES_PATH: "/home/vsoc-01/msh-cuttlefish-leases.json"
    ports:
      - "7037:7037"
      - "6520:6520"

volumes:
  cuttlefish-home:
```

**2. Start:**

```bash
docker compose up -d
```

---

## Quick Start — Kubernetes

```bash
helm install shepherd deploy/helm/marathon-shepherd \
  --namespace shepherd --create-namespace \
  --set-file config.content=./msh.yaml
```

The `marathon-shepherd` chart deploys the manager, and `deploy/helm/shepherd-adapter` deploys an
adapter when the devices hang off a Kubernetes node. The manager runs a single replica on purpose —
it locks its database at startup, and a second one exits — and keeps `msh.yaml` in a Secret. Point
`database.url` at Postgres to keep state there instead of the volume.
[docs/kubernetes.md](docs/kubernetes.md) covers a small cluster without a load balancer, phones
plugged into a node, moving from Docker Compose and delivery from Jenkins.

## Manager API

Every `/api/v1/...` call carries `Authorization: Bearer <key>` or the web UI's session cookie;
`/mcp` takes bearer keys only. Sign-in, the probes, metrics and documentation endpoints are public. The full description is served at `/openapi.yaml`,
with Swagger UI at `/docs`.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/sessions` | Create a session: `READY`, or `PENDING` in the queue |
| `POST` | `/api/v1/sessions/{id}/wait` | Long-poll a queued session (≤ 30 s per call) |
| `POST` | `/api/v1/sessions/{id}/heartbeat` | Keep a session with an idle timeout alive |
| `POST` | `/api/v1/sessions/{id}/extend` | Move the expiry, on providers that renew leases |
| `GET` | `/api/v1/sessions` | List sessions (`status`, `owner=me`) |
| `GET` | `/api/v1/sessions/{id}` | One session |
| `DELETE` | `/api/v1/sessions/{id}` | Release a session |
| `GET` | `/api/v1/devices` | Devices and pools (`state`, `provider`, `deviceType`, `api`, `label`, `refresh`) |
| `GET` | `/api/v1/devices/{id}` | One device |
| `PUT`, `DELETE` | `/api/v1/devices/{id}/maintenance` | Take a device out of allocation, or return it (admin) |
| `GET` | `/api/v1/events` | Session, device, provider and lease events (SSE, `Last-Event-ID`) |
| `GET` | `/api/v1/providers` | Providers from `msh.yaml` and self-registered adapters |
| `POST` | `/api/v1/providers/register` | Adapter self-registration (`provider` role) |
| `DELETE` | `/api/v1/providers/{name}` | Remove a registration (admin) |
| `POST` | `/api/v1/auth/login`, `GET /api/v1/auth/methods` | Password sign-in (sets the session cookie), sign-in options (public) |
| `GET` | `/auth/oidc/{provider}/login` | Sign in with an OIDC provider (public) |
| `POST` | `/api/v1/auth/tokens`, `/api/v1/auth/password` | Personal token or password change with a password, for CLIs (public) |
| `GET`, `POST` | `/api/v1/auth/session`, `/api/v1/auth/logout` | The browser session |
| `GET`, `POST`, `DELETE` | `/api/v1/me/tokens`, `POST /api/v1/me/password` | Your personal tokens and password |
| `GET`, `POST`, `PATCH`, `DELETE` | `/api/v1/admin/users[/{id}[/password\|/tokens]]` | People who sign in (admin) |
| `GET` | `/api/v1/me` | The caller, its quota and its usage |
| `GET` | `/api/v1/audit` | Audit log |
| `GET`, `POST`, `PATCH`, `DELETE` | `/api/v1/admin/clients[/{id}[/rotate]]` | API clients and keys (admin) |
| `GET`, `PUT` | `/api/v1/config`, `POST /api/v1/config/reload` | Configuration (admin) |
| `POST` | `/mcp` | Model Context Protocol endpoint for AI agents |
| `GET` | `/live`, `/ready`, `/health`, `/metrics` | Probes and Prometheus metrics (public) |
| `GET` | `/openapi.yaml`, `/docs` | API description and Swagger UI (public) |

**Create session — request body:**

```json
{
  "maxDevices": 3,
  "api": ">=34",
  "ttlSeconds": 3600,
  "deviceType": "physical"
}
```

`maxDevices` is the upper bound for parallelism, not a strict requirement. `deviceType` is optional (`"physical"` or `"emulator"`). Omit it to use all capable providers. `api` is optional and accepts exact, range, and set selectors such as `"34"`, `">=34"`, `"<34"`, `"34+"`, `"33..35"`, and `"33,34,35"`.

Legacy `devices` and `apiLevel` request fields are still accepted for backward compatibility.

**Create session — response:**

```json
{
  "id": "sess_a1b2c3d4",
  "status": "PENDING",
  "requestedDevices": 3,
  "allocatedDevices": 0,
  "api": ">=34",
  "deviceType": "physical",
  "queuePosition": 1,
  "adbServers": [],
  "createdAt": "2026-01-01T12:00:00Z",
  "expiresAt": "2026-01-01T13:00:00Z"
}
```

Create can return either `READY` or `PENDING`:

- `READY`: at least one matching device was allocated immediately and `adbServers` is populated.
- `PENDING`: matching devices are registered but currently busy, so the session is placed into the manager FIFO queue.

If no registered devices can ever satisfy the request, the manager fails immediately instead of enqueuing.

**Wait for queued session — request body:**

```json
{
  "timeoutSeconds": 20
}
```

`POST /api/v1/sessions/{id}/wait` long-polls the queue, refreshes the session heartbeat, and returns the updated session payload. The manager allocates the head of the queue as soon as at least one matching device becomes free.

## Web UI

Open the manager's address in a browser (`http://localhost:6037/` goes to `/ui/`) and sign in.
Everyone sees the fleet: an overview, devices with their state and holder, sessions, providers and
a live activity feed. People who may take devices take one from its row, or create a session for
several and copy the `adb` commands that reach them. Admins also put devices into maintenance and
manage users, API clients, the audit log and the configuration. Under *Your account* everyone
changes their password and creates personal API tokens.

The UI is plain HTML, CSS and JavaScript served by the manager: no build step, nothing loaded from
other hosts, and a strict Content-Security-Policy. It uses the same `/api/v1` as every other
client, through the session cookie and its CSRF token.

## Signing in

People sign in with a username and password by default. Add LDAP / Active Directory or any
number of OIDC providers (Keycloak, Microsoft Entra ID, Okta, Google, GitLab) under `auth` in
`msh.yaml`; their groups decide who is an admin, a user or a viewer.

```yaml
auth:
  publicUrl: "https://shepherd.example.com"
  oidc:
    - id: keycloak
      issuer: "https://sso.example.com/realms/engineering"
      clientId: marathon-shepherd
      clientSecretEnv: MSH_OIDC_KEYCLOAK_SECRET
      roleMapping: { shepherd-admins: admin, mobile-qa: user }
```

Browser sessions use an `HttpOnly` cookie with a CSRF token, repeated failures lock a username
out, and `mshctl login` gives people a personal token for the command line.
[docs/authentication.md](docs/authentication.md) covers local accounts, LDAP, OIDC and sessions.

## Clients

| Client | For |
|--------|-----|
| [`mshctl`](manager/cli) | Operators and scripts: sessions, devices, providers, clients, audit, events |
| [`manager/client`](manager/client) | Kotlin and the JVM: `ShepherdClient`, with `acquire` and `withSession` |
| [`clients/python`](clients/python) | Python 3.9+, no dependencies: `with client.session(...)`, plus a pytest fixture |
| [MCP](docs/mcp.md) | AI agents, through `/mcp` or the `shepherd-mcp` binary |
| HTTP | Everything else: `curl -H "Authorization: Bearer $MSH_TOKEN"` |

[docs/clients.md](docs/clients.md) covers issuing keys, the session lifecycle and an example
for each client.

## Devices for AI agents

The manager speaks the [Model Context Protocol](https://modelcontextprotocol.io), so an agent
can list, lease and return devices itself:

```bash
claude mcp add --transport http shepherd https://shepherd.example.com/mcp \
  --header "Authorization: Bearer msh_..."
```

`shepherd-mcp` does the same over stdio for hosts that start MCP servers as processes, and
releases whatever it leased when the agent goes away. Agent sessions are capped, short-lived
and reclaimed when the agent stops checking in. See [docs/mcp.md](docs/mcp.md).

## Observability

The manager and every adapter expose Prometheus metrics at `/metrics`: sessions by status,
queue depth and wait times, devices per provider and state, allocation outcomes, adapter call
latency, plus JVM and HTTP metrics. `/health` reports provider health and pool sizes from the
background poller, and `/ready` reports whether the manager can serve the API.

[deploy/observability](deploy/observability) has a Grafana dashboard, Prometheus alert rules
and a scrape config, with a table of every metric. `/metrics` is public, so keep the port on a
private network.

## Adapter API

All adapters expose the same contract. `/health` is public; the rest require `Authorization: Bearer <ADAPTER_SECRET>`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Liveness check |
| `GET` | `/status` | Pool counts, access descriptors, inventory, capabilities |
| `POST` | `/acquire` | Allocate a lease |
| `DELETE` | `/release/{leaseId}` | Release a lease |

The `status` and `acquire` responses include structured `access` (ADB connection descriptors), `inventory` (device profiles with type, API level, model), and `capabilities` (what this adapter supports). The manager uses all three to route sessions.

## Marathon Compatibility

Shepherd remains transport-focused on the manager side, but the Jenkins integration is now Marathon-aware. `runMarathonWithShepherd(...)` injects runtime `adbServers` through a temporary Gradle init script, so Jenkins no longer has to generate a Marathonfile in Groovy.

Runtime behavior:

- `marathonfile == null`: Gradle keeps using the project's normal Marathon config source.
- `marathonfile != null`: Jenkins passes an explicit file override to the init script.
- runtime `adbServers` are injected immediately before the Gradle task runs.

| Scenario | Support |
|----------|---------|
| Physical device testing (ADB) | ✓ |
| Emulator testing (ADB) | ✓ |
| Cuttlefish (ADB on :6520) | ✓ |
| Multi-host allocation | ✓ |
| API targeting | ✓ (`34`, `>=34`, `<34`, `34+`, `33..35`, `33,34,35`) |
| Filter by device type per session | ✓ (`deviceType: "physical"` / `"emulator"`) |

## Configuration

### `msh.yaml`

```yaml
providers:
  - name: "device-rack-1"
    url: "http://192.168.1.10:7037"
    # Optional only when direct device access must use another host
    # accessHost: "192.168.1.11"
    secret: "strong-random-secret"         # openssl rand -hex 32

  - name: "emu-farm-1"
    url: "http://192.168.1.20:7037"
    # Example: when farm-server publishes adb on another host
    # accessHost: "192.168.1.21"
    secret: "another-secret"
```

### Manager

| Variable / Flag | Default | Description |
|-----------------|---------|-------------|
| `--config <path>` / `MSH_CONFIG` | `msh.yaml` in CWD | Config file path |
| `MSH_PORT` | `6037` | HTTP port |
| `MSH_DATA_DIR` | `~/.msh` | State directory: SQLite database, generated admin password, lock file |
| `MSH_ADMIN_PASSWORD` | _(generated)_ | First password of the `admin` user. Without it the manager generates a one-time password on first start, prints it once and writes it to `<MSH_DATA_DIR>/initial-admin-password` |
| `MSH_ADMIN_TOKEN` | _(none)_ | Optional static admin API key for automation |
| `MSH_DB_URL` | _(SQLite)_ | `jdbc:postgresql://...` to keep state in Postgres. One manager per database either way: the second one exits |

Provider config note:
- `accessHost` is optional.
- If omitted, manager uses the host part of `provider.url` when constructing `adbServers`.
- Set it only when the direct device-access endpoint lives on another host than the adapter control plane.

### Adapter environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ADAPTER_PORT` | `7037` | HTTP port |
| `ADAPTER_ADB_PORT` | `5037` / `6520` | Upstream adb daemon port used by adapters that proxy or expose adb |
| `ADAPTER_SECRET` | _(blank = no auth)_ | Bearer token; blank for local dev only |
| `MSH_MANAGER_URL` | _(none)_ | Register with this manager instead of being listed in its `msh.yaml` |
| `MSH_REGISTRATION_TOKEN` | _(none)_ | API key with the `provider` role, for registration |
| `ADAPTER_PUBLIC_URL` | _(none)_ | How the manager reaches this adapter; required when registering |
| `ADAPTER_NAME` | `<hostname>-<type>` | Provider name to register under |
| `ADAPTER_ACCESS_HOST` | _(host of the public URL)_ | Direct device-access host, when it differs |
| `MSH_REGISTRATION_INTERVAL_SECONDS` | `30` | How often to heartbeat the registration |

Recommendation:
- For dockerized `shepherd-adb` and `shepherd-farm` hosts, publish the upstream adb daemon on host port `5038` and set `ADAPTER_ADB_PORT=5038`.
- Keep the container-internal adb daemon on `5037`; only the host-facing published port changes.

| Variable | Adapter | Default | Description |
|----------|---------|---------|-------------|
| `ADB_LEASES_PATH` | adb | `~/.msh/adb-leases.json` | Persistent physical-device lease state |
| `ADB_DEVICE_LABELS_FILE` | adb | _(none)_ | JSON file mapping serial → labels, which sessions can then ask for |
| `ADB_PROXY_PORT_RANGE` | adb | _(ephemeral)_ | Lease-scoped adb proxy ports that must be reachable by Marathon |
| `FARM_SERVER_HOST` | farm | `127.0.0.1` | Local farm-server host |
| `FARM_SERVER_PORT` | farm | `8080` | Local farm-server port |
| `FARM_SUPPORTED_API_LEVELS` | farm | _(all)_ | Comma-separated, e.g. `33,34,35` |
| `CUTTLEFISH_ORCHESTRATOR_URL` | cuttlefish | `https://127.0.0.1:2443` | Base URL of the Host/Cloud Orchestrator REST API |
| `CUTTLEFISH_ORCHESTRATOR_INSECURE_TLS` | cuttlefish | `true` for `https://...`, else `false` | Disable TLS certificate validation for self-signed orchestrator endpoints |
| `CUTTLEFISH_ORCHESTRATOR_TOKEN` | cuttlefish | _(empty)_ | Optional Bearer token for the orchestrator API |
| `CUTTLEFISH_ORCHESTRATOR_LEASES_PATH` | cuttlefish | `~/.msh/cuttlefish-orchestrator-leases.json` | Lease state (survives restarts) |

## Jenkins

The entrypoint is `vars/runMarathonWithShepherd.groovy`. It is a high-level step: it allocates devices, waits in the manager queue if capacity is busy, injects runtime ADB endpoints into Gradle through a temporary init script, runs the requested Marathon task, then always releases the session.

```groovy
@Library('marathon-shepherd') _

runMarathonWithShepherd(
    managerUrl: 'http://manager-host:6037',
    maxDevices: 4,
    deviceType: 'emulator',
    api: '>=34',
    queueTimeoutSeconds: 900,
    task: 'marathon',
    marathonfile: 'ci/Marathonfile.yaml' // optional override
)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `managerUrl` | `$MSH_URL` or `http://localhost:6037` | Manager URL |
| `maxDevices` | `1` | Maximum device parallelism requested from Shepherd |
| `api` | _(all)_ | API selector: `34`, `>=34`, `<34`, `34+`, `33..35`, `33,34,35` |
| `deviceType` | _(all)_ | `"physical"` or `"emulator"` |
| `queueTimeoutSeconds` | `900` | Hard limit for total queue wait before the Jenkins step fails and releases the session |
| `task` | `"marathon"` | Gradle task name to run through `./gradlew` |
| `marathonfile` | `null` | Optional explicit Marathonfile path override |

Step behavior:

1. Create a session in manager with `maxDevices`, `deviceType`, and `api`.
2. Fail immediately if no registered devices can ever satisfy the request.
3. Wait in the manager FIFO queue when matching devices are registered but currently busy.
4. Fail the Jenkins step if the session stays `PENDING` longer than `queueTimeoutSeconds`, then release the session in `finally`.
5. Write runtime files under `.msh/jenkins/<sessionId>/`.
6. Run `./gradlew -I .msh/jenkins/<sessionId>/shepherd.init.gradle <task>`.
7. Release the session in `finally`.

Runtime files:

- `adb-servers.json`: exact runtime endpoints returned by manager
- `shepherd.init.gradle`: temporary init script that injects `adbServers` into Marathon
- `marathonfile.path`: written only when `marathonfile` override is provided

Jenkins overview is intentionally grouped into labeled phases:

- `Shepherd / Queue Session`
- `Shepherd / Wait For Devices`
- `Marathon / Prepare Runtime Directory`
- `Marathon / Run Task`
- `Shepherd / Release Session`

`vars/shepherdTest.groovy` is no longer the supported entrypoint.

## Troubleshooting

```bash
# Manager
curl -sf http://localhost:6037/live | jq
curl -sf http://localhost:6037/health | jq
mshctl health && mshctl devices

# Adapter
curl -sf http://<host>:7037/health
curl -sf -H "Authorization: Bearer <secret>" http://<host>:7037/status | jq

# Logs
docker compose logs -f
```

| Symptom | Check |
|---------|-------|
| Manager started, but adapters are not connected yet | `/live` should be `200`; `/health` will stay `503` until at least one provider is healthy |
| `401` from adapter | `msh.yaml` secret vs `ADAPTER_SECRET` on the adapter host |
| Provider unhealthy, manager healthy | Adapter logs, adapter `/health`, firewall |
| Session `FAILED`, 0 devices | `mshctl devices` — pool available? API level match? |
| Physical rack never satisfies the `api` selector | All devices on the rack must share the same API level |
| Marathon can't reach devices | For `shepherd-adb`, publish `ADB_PROXY_PORT_RANGE`; for farm/cuttlefish, expose the advertised adb port |

## Building

```bash
./gradlew test
./gradlew build

./gradlew :manager:service:installDist   # run locally
./gradlew :manager:cli:installDist       # mshctl

docker build -f deploy/Dockerfile                     -t "$NS"/marathon-shepherd:latest .
docker build -f deploy/Dockerfile.shepherd-adb        -t "$NS"/shepherd-adb:latest .
docker build -f deploy/Dockerfile.shepherd-farm       -t "$NS"/shepherd-farm:latest .
docker build -f deploy/Dockerfile.shepherd-cuttlefish -t "$NS"/shepherd-cuttlefish:latest .

# Or push all four to your own registry namespace in one go:
MSH_REGISTRY_NAMESPACE="$NS" ./scripts/publish_dockerhub.sh --tag 0.2.0

docker push "$NS"/marathon-shepherd:latest
docker push "$NS"/shepherd-adb:latest
docker push "$NS"/shepherd-farm:latest
docker push "$NS"/shepherd-cuttlefish:latest
```

## Integration Testing

Run end-to-end scenarios from the repository root.

### Unified runner

```bash
./tests/run_tests.sh
```

Console mode behavior:
- without `--console=plain`: `tests/run_tests.sh` renders a Python `rich` dashboard in TTY mode
- the dashboard shows test configuration, prerequisite statuses, stage progress, and the last 15 log lines of the active stage
- with `--console=plain`: logs stay backend-friendly and deterministic (timestamps + plain text)
- Gradle runs with `--continue` by default; use `--fail-fast` (or `--no-continue`) to stop on the first failing Gradle task
- for the interactive dashboard, install the local runner dependency once:

```bash
python3 -m venv .msh-runner-venv
.msh-runner-venv/bin/python -m pip install -r tests/requirements_runner.txt
```

Core stages always run:
- `./gradlew test`
- `tests/component/cli/console.sh`

Environment-dependent stages:
- Docker integration runs by default, skip via `--skip-docker-integration`
- Docker compose build/start logs are dynamic in interactive mode and compact in plain-log mode; force live compose output with `MSH_VERBOSE_DOCKER_COMPOSE=true`
- Real-device integration also runs by default, skip via `--skip-real-device-integration`
- `--skip-environment-integration` (both flags above)
- `--skip-jenkins-harness` (skips Groovy Jenkins harness scenarios inside integration scripts)
- `--fail-fast` / `--no-continue` (disables default Gradle `--continue`)

Real-device stage tuning:
- `MSH_REAL_DEVICE_MANAGER_URL` (default: `${MSH_URL}` or `http://localhost:6037`)
- `MSH_REAL_DEVICE_REQUESTED_DEVICES` (default: `1`)
- `MSH_REAL_DEVICE_API_LEVEL` (default: `34`)
- `MSH_REAL_DEVICE_TTL_SECONDS` (default: `120`)
- `MSH_REAL_DEVICE_TYPE` (`physical` or `emulator`, default: `physical`)

### Smoke — published Docker images

Tests each adapter image and the manager image independently. Green = published images start, enforce auth, and satisfy their API contracts.

```bash
tests/component/docker/smoke_docker.sh
```

What it validates per image:
- `shepherd-adb` — `/health` public; `/status` requires Bearer; `/acquire` returns 503 with no USB devices; capabilities advertise `physical`
- `shepherd-farm` — `/health` healthy; `/acquire` allocates from harness and returns `leaseId`; `/release` restores pool count
- `shepherd-cuttlefish` — same acquire/release cycle using the Cloud Orchestrator REST harness
- manager — `/live`, `/health` (all 3 providers HEALTHY), session POST/GET/DELETE, `?status=` filter, config endpoint

Override images to test a specific tag:
```bash
MSH_MANAGER_IMAGE="$NS"/marathon-shepherd:0.2.0 \
MSH_SHEPHERD_ADB_IMAGE="$NS"/shepherd-adb:0.2.0 \
MSH_SHEPHERD_FARM_IMAGE="$NS"/shepherd-farm:0.2.0 \
MSH_SHEPHERD_CUTTLEFISH_IMAGE="$NS"/shepherd-cuttlefish:0.2.0 \
tests/component/docker/smoke_docker.sh
```

### Console mode (no Docker Compose, isolated temp state)

```bash
./gradlew :manager:service:installDist
tests/component/cli/console.sh
```

What it validates:
- manager API session lifecycle (create/release)
- request validation (`deviceType`)
- targeted cuttlefish allocation path (`api=35`) when cuttlefish inventory is available
- Jenkins shared-library step (`vars/runMarathonWithShepherd.groovy`) through a local Groovy harness
- no writes to default `~/.msh` state path

### Docker Compose mode (manager + real adapters, locally built images)

```bash
tests/integration/docker_compose.sh
```

What it validates:
- manager + all real adapters wiring in containers (`shepherd-adb`, `shepherd-farm`, `shepherd-cuttlefish`)
- backend integration for adapter dependencies (`adb` daemon, farm-server contract service, Cloud Orchestrator REST contract)
- successful + partial emulator allocations and physical-capacity behavior
- targeted cuttlefish allocation path (`api=35`) when cuttlefish inventory is available (otherwise explicitly skipped with reason)
- release semantics
- Jenkins shared-library step in containerized topology

### Docker e2e scenarios (dedicated queue / topology coverage)

```bash
tests/e2e/docker/e2e_docker.sh
```

What it validates:
- mixed cluster, farm-only, and cuttlefish-only topologies via live manager config switching
- partial allocation in a constrained farm-only topology
- immediate `503` when no registered devices match the request
- queue semantics (`PENDING -> /wait -> READY`) in a deterministic farm-only cluster with fixed capacity

### End-to-end APK test (requires connected device)

Full system test: Shepherd allocates a real device, APKs are installed and run **through the session-scoped ADB proxy**, instrumentation passes, session is released. Green = the complete production flow works.

```bash
./gradlew :manager:service:installDist :adapter:shepherd-adb:installDist
tests/e2e/device/e2e_apk.sh
```

What it validates:
- manager + shepherd-adb start and become healthy
- session allocation returns a valid ADB proxy endpoint
- Shepherd proxy exposes exactly 1 device (lease isolation)
- `adb install` and `am instrument` work through the proxy port
- `android/architecture-samples` instrumentation tests pass on the connected device
- session release clears all active sessions

Requires: a physical device or running emulator visible to `adb devices`. The API level is auto-detected. The pinned sample APKs are **not committed** — build them once with `./scripts/build_test_apk.sh` (see [tests/public_ui/README.md](tests/public_ui/README.md)); they land in the git-ignored `tests/public_ui/apks/`.

Optional env vars: `ADB_SERIAL` (force a specific device), `MSH_E2E_TTL` (session TTL in seconds, default 300).

### Jenkins shared-library harness only

```bash
MSH_URL=http://localhost:6037 groovy tests/integration/jenkins_harness.groovy
```

### Public pinned UI sample (direct ADB, no Shepherd)

Runs the same instrumentation suite but connects directly to ADB, bypassing Shepherd. Useful for verifying APKs and device state independently.

```bash
scripts/build_test_apk.sh   # build APKs once (clones repo, builds, caches)
tests/public_ui/run_tests.sh # install + am instrument directly via adb
```

Pinned repository: `android/architecture-samples` @ `ee66e1526b84c026615df032c705842b7d2a521f`

Pinned AndroidX test stack override for cached APKs: `core 1.7.0`, `ext 1.3.0`, `rules 1.7.0`, `runner 1.7.0`, `espresso 3.7.0`

## Project Structure

```
adapter/
  contract/             Adapter wire types, shared with the manager
  api/                  Shared adapter runtime: routes, auth, metrics, self-registration
  shepherd-adb/         Physical-device adapter
  shepherd-farm/        Emulator adapter
  shepherd-cuttlefish/  Cuttlefish adapter backed by Cloud Orchestrator REST
manager/
  protocol/             Manager API wire types, shared by service, clients and MCP
  service/              REST API, MCP endpoint, session lifecycle, SQLite or Postgres state
  client/               Kotlin client library
  cli/                  mshctl operator CLI
  mcp/                  MCP tools and the shepherd-mcp stdio server
clients/python/         Dependency-free Python client
docs/                   Guides: clients, MCP, signing in, Kubernetes; the roadmap
deploy/                 Dockerfiles, host compose files, Helm chart, msh.yaml.example, observability
vars/                   Jenkins Shared Library steps
tests/                  Unit, component, integration, e2e, scale and Jenkins coverage
  component/docker/     Test-only images and the smoke compose stack
  helpers/              Shared shell helpers used across tiers
scripts/                Auxiliary helper scripts (sample APK build, image publishing)
Jenkinsfile             CI pipeline for this repository
.github/workflows/      GitHub Actions: CI, release, on-demand Docker tiers
```

`deploy/` holds what you deploy; `tests/component/docker/` holds what the test suite
builds. They are deliberately separate — do not cross-reference them.

**Stack:** Kotlin 2.4 · JVM 21 · Ktor 3.5 · Exposed · SQLite or Postgres · Micrometer · kaml · Clikt · MCP Kotlin SDK

## Security

Every `/api/v1` call needs an API key or a signed-in browser session, and sessions belong to
the client or person that created them. Passwords are stored as PBKDF2 hashes, browser sessions
use `HttpOnly` `SameSite` cookies with a CSRF token, and repeated failed sign-ins lock a username
out. What stays open: `/live`, `/ready`, `/health`, `/metrics` and the API docs,
which describe the fleet, and the per-lease adb proxies, which are unauthenticated for the
duration of a lease. There is no TLS in the manager, so terminate it in front and keep the
port on a private network. Adapters authenticate with bearer tokens.

Read [SECURITY.md](SECURITY.md) for the full threat model and trust boundaries before
deploying, and to report a vulnerability.

## Contributing

Contributions are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers prerequisites, how to
run the right slice of the test suite, and code style. The short version:

```bash
./gradlew build -x test          # build
./tests/run_tests.sh --only unit:kotlin   # fast gate: no Docker, no device, no Groovy
./gradlew ktlintCheck            # style
```

Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).

## Support

- Bugs and feature requests: [GitHub Issues](https://github.com/rus-artur4ik/marathon-shepherd/issues)
- Security vulnerabilities: see [SECURITY.md](SECURITY.md) — please do not open a public issue

## License

Licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for trademark and
attribution statements, and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for the
licences of bundled dependencies.
