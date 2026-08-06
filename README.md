# Marathon Shepherd

> One REST API that hands out Android test devices — physical racks, emulator farms and
> Cuttlefish hosts alike — to any CI job, so your pipeline asks for "2 devices on API 34"
> instead of hard-coding which machine has them.

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![JVM 21](https://img.shields.io/badge/jvm-21-437291)](https://adoptium.net/)
[![Kotlin 2.2](https://img.shields.io/badge/kotlin-2.2-7F52FF)](https://kotlinlang.org/)
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

> **Not affiliated with Marathon Labs.** "Marathon" is used descriptively to name the test
> runner this project feeds devices to. See [NOTICE](NOTICE).

> **Status: early.** Version 0.1.0; container images are published to GHCR.
> The HTTP API and config schema may still change. See [CHANGELOG.md](CHANGELOG.md).

## Contents

- [How It Works](#how-it-works)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [HTTP API](#http-api)
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
 │  mshctl · Jenkins pipeline · marathon     │
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

**4. Verify and create a session**

```bash
mshctl health
mshctl devices
mshctl create --devices 2 --api 34
mshctl list
mshctl release --id sess_abc123
```

---

## Quick Start — Docker

> Images are published to the GitHub Container Registry for `linux/amd64` and
> `linux/arm64`: `ghcr.io/rus-artur4ik/marathon-shepherd`, `shepherd-adb`, `shepherd-farm`
> and `shepherd-cuttlefish`, each tagged with the release version (`0.1.0`), its minor line
> (`0.1`) and `latest`. The examples pin `0.1.0`.
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
    image: ghcr.io/rus-artur4ik/marathon-shepherd:0.1.0
    container_name: shepherd-manager
    restart: unless-stopped
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
    image: ghcr.io/rus-artur4ik/shepherd-adb:0.1.0
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
    image: ghcr.io/rus-artur4ik/shepherd-farm:0.1.0
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
    image: ghcr.io/rus-artur4ik/shepherd-cuttlefish:0.1.0
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

## Manager API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/sessions` | Create session and enqueue allocation |
| `POST` | `/api/v1/sessions/{id}/wait` | Wait for queued session progress and refresh heartbeat |
| `GET` | `/api/v1/sessions` | List active sessions |
| `GET` | `/api/v1/sessions?status=READY` | List sessions filtered by status |
| `GET` | `/api/v1/sessions/{id}` | Get session |
| `DELETE` | `/api/v1/sessions/{id}` | Release session |
| `GET` | `/api/v1/devices` | Aggregate provider inventory |
| `GET` | `/live` | Manager process liveness |
| `GET` | `/health` | Manager readiness + provider health |
| `GET` | `/api/v1/config` | Active config |
| `PUT` | `/api/v1/config` | Update config (409 if a removed provider has active sessions) |
| `POST` | `/api/v1/config/reload` | Reload config from disk |

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
| `MSH_DATA_DIR` | `~/.msh` | SQLite state directory |

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

Recommendation:
- For dockerized `shepherd-adb` and `shepherd-farm` hosts, publish the upstream adb daemon on host port `5038` and set `ADAPTER_ADB_PORT=5038`.
- Keep the container-internal adb daemon on `5037`; only the host-facing published port changes.

| Variable | Adapter | Default | Description |
|----------|---------|---------|-------------|
| `ADB_LEASES_PATH` | adb | `~/.msh/adb-leases.json` | Persistent physical-device lease state |
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
MSH_REGISTRY_NAMESPACE="$NS" ./scripts/publish_dockerhub.sh --tag 0.1.0

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
MSH_MANAGER_IMAGE="$NS"/marathon-shepherd:0.1.0 \
MSH_SHEPHERD_ADB_IMAGE="$NS"/shepherd-adb:0.1.0 \
MSH_SHEPHERD_FARM_IMAGE="$NS"/shepherd-farm:0.1.0 \
MSH_SHEPHERD_CUTTLEFISH_IMAGE="$NS"/shepherd-cuttlefish:0.1.0 \
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
  api/                  Shared adapter contract, auth, server bootstrap
  shepherd-adb/         Physical-device adapter
  shepherd-farm/        Emulator adapter
  shepherd-cuttlefish/  Cuttlefish adapter backed by Cloud Orchestrator REST
manager/
  service/              REST API, session lifecycle, SQLite state
  cli/                  mshctl operator CLI
deploy/                 Production Dockerfiles, host compose files, msh.yaml.example
vars/                   Jenkins Shared Library steps
tests/                  Unit, component, integration, e2e, scale and Jenkins coverage
  component/docker/     Test-only images and the smoke compose stack
  helpers/              Shared shell helpers used across tiers
scripts/                Auxiliary helper scripts (sample APK build, image publishing)
Jenkinsfile             CI pipeline for this repository
.github/workflows/      GitHub Actions CI
```

`deploy/` holds what you deploy; `tests/component/docker/` holds what the test suite
builds. They are deliberately separate — do not cross-reference them.

**Stack:** Kotlin 2.2 · JVM 21 · Ktor 2.3 · Exposed · SQLite · kaml · Clikt

## Security

The Manager API is **unauthenticated by design** and must not be exposed to an untrusted
network — anyone who can reach it can allocate every device in the fleet. Adapters
authenticate with bearer tokens.

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
