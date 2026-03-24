# Marathon Shepherd

Device orchestration layer for [Marathon](https://marathonlabs.github.io/marathon/). One API to allocate devices across physical racks, emulator farms, and Cuttlefish hosts — from any CI job or CLI.

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
 │ adb-server  │  │ farm-server   │  │  cvdr                │
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

Docker images are published to DockerHub. No repo clone needed — just create config and compose files, then start.

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
    image: rusartur4ik/marathon-shepherd:latest
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
    image: ubuntu:24.04
    container_name: adb-server
    command: >
      bash -c "apt-get update && apt-get install -y --no-install-recommends adb
      && adb -a server nodaemon"
    privileged: true
    devices:
      - /dev/bus/usb
    networks:
      - shepherd
    restart: unless-stopped
    ports:
      - "5038:5037"

  shepherd-adb:
    image: rusartur4ik/shepherd-adb:latest
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
    image: rusartur4ik/shepherd-farm:latest
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
    image: rusartur4ik/shepherd-cuttlefish:latest
    container_name: shepherd-cuttlefish
    privileged: true
    devices:
      - /dev/kvm
    volumes:
      - /usr/local/bin/cvdr:/usr/local/bin/cvdr:ro
      - cuttlefish-home:/home/vsoc-01
    restart: unless-stopped
    environment:
      ADAPTER_ADB_PORT: "6520"
      ADAPTER_SECRET: "change-me"
      CVDR_PATH: "/usr/local/bin/cvdr"
      CVDR_LEASES_PATH: "/home/vsoc-01/msh-cuttlefish-leases.json"
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
| `POST` | `/api/v1/sessions` | Create session, allocate devices |
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
  "devices": 3,
  "apiLevel": "34",
  "ttlSeconds": 3600,
  "deviceType": "physical"
}
```

`deviceType` is optional (`"physical"` or `"emulator"`). Omit to use all capable providers.

**Create session — response:**

```json
{
  "id": "sess_a1b2c3d4",
  "status": "READY",
  "requestedDevices": 3,
  "allocatedDevices": 3,
  "adbServers": [{ "host": "192.168.1.10", "port": 7600 }],
  "createdAt": "2026-01-01T12:00:00Z",
  "expiresAt": "2026-01-01T13:00:00Z"
}
```

The CI layer receives `adbServers` and constructs the Marathonfile itself — Shepherd is not Marathon-specific.

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

Shepherd returns `adbServers` for Marathon's `vendorConfiguration`. Construct the Marathonfile in your CI layer — this keeps retry strategies, timeouts, and output config under your control.

| Scenario | Support |
|----------|---------|
| Physical device testing (ADB) | ✓ |
| Emulator testing (ADB) | ✓ |
| Cuttlefish (ADB on :6520) | ✓ |
| Multi-host allocation | ✓ |
| API-level targeting | ✓ (selective for physical, farm, and cuttlefish) |
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
| `CVDR_PATH` | cuttlefish | `cvdr` | Path to cvdr binary |
| `CVDR_LEASES_PATH` | cuttlefish | `~/.msh/cuttlefish-leases.json` | Lease state (survives restarts) |

## Jenkins

The entrypoint is `vars/shepherdTest.groovy`. The step allocates devices, passes `adbServers` to a closure where you build and run marathon with your own Marathonfile, then releases in `finally`.

```groovy
@Library('marathon-shepherd') _

shepherdTest(
    managerUrl: 'http://manager-host:6037',
    devices: 4,
    api: '34'
) { adbServers ->
    // adbServers is a list of {host, port} maps
    // build your Marathonfile here and run marathon
    writeFile file: 'Marathonfile.yaml', text: buildMarathonfile(adbServers)
    sh 'marathon --marathonfile Marathonfile.yaml'
}
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `managerUrl` | `$MSH_URL` or `http://localhost:6037` | Manager URL |
| `devices` | `1` | Requested device count |
| `api` | `"34"` | Android API level |
| `ttl` | `3600` | Session TTL in seconds |
| `deviceType` | _(all)_ | `"physical"` or `"emulator"` |

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
| Physical rack never satisfies `apiLevel` | All devices on the rack must share the same API level |
| Marathon can't reach devices | For `shepherd-adb`, publish `ADB_PROXY_PORT_RANGE`; for farm/cuttlefish, expose the advertised adb port |

## Building

```bash
./gradlew test
./gradlew build

./gradlew :manager:service:installDist   # run locally
./gradlew :manager:cli:installDist       # mshctl

docker build -f deploy/Dockerfile                     -t rusartur4ik/marathon-shepherd:latest .
docker build -f deploy/Dockerfile.shepherd-adb        -t rusartur4ik/shepherd-adb:latest .
docker build -f deploy/Dockerfile.shepherd-farm       -t rusartur4ik/shepherd-farm:latest .
docker build -f deploy/Dockerfile.shepherd-cuttlefish -t rusartur4ik/shepherd-cuttlefish:latest .

docker push rusartur4ik/marathon-shepherd:latest
docker push rusartur4ik/shepherd-adb:latest
docker push rusartur4ik/shepherd-farm:latest
docker push rusartur4ik/shepherd-cuttlefish:latest
```

## Integration Testing

Run end-to-end scenarios from the repository root.

### Unified runner

```bash
./scripts/run_tests.sh
```

Console mode behavior:
- without `--console=plain`: `scripts/run_tests.sh` renders a Python `rich` dashboard in TTY mode
- the dashboard shows test configuration, prerequisite statuses, stage progress, and the last 15 log lines of the active stage
- with `--console=plain`: logs stay backend-friendly and deterministic (timestamps + plain text)
- Gradle runs with `--continue` by default; use `--fail-fast` (or `--no-continue`) to stop on the first failing Gradle task
- for the interactive dashboard, install the local runner dependency once:

```bash
python3 -m venv .msh-runner-venv
.msh-runner-venv/bin/python -m pip install -r scripts/requirements_runner.txt
```

Core stages always run:
- `./gradlew test`
- `scripts/integration/console.sh`

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
scripts/integration/smoke_docker.sh
```

What it validates per image:
- `shepherd-adb` — `/health` public; `/status` requires Bearer; `/acquire` returns 503 with no USB devices; capabilities advertise `physical`
- `shepherd-farm` — `/health` healthy; `/acquire` allocates from harness and returns `leaseId`; `/release` restores pool count
- `shepherd-cuttlefish` — same acquire/release cycle using the cvdr harness
- manager — `/live`, `/health` (all 3 providers HEALTHY), session POST/GET/DELETE, `?status=` filter, config endpoint

Override images to test a specific tag:
```bash
MSH_MANAGER_IMAGE=rusartur4ik/marathon-shepherd:v1.2 \
MSH_SHEPHERD_ADB_IMAGE=rusartur4ik/shepherd-adb:v1.2 \
MSH_SHEPHERD_FARM_IMAGE=rusartur4ik/shepherd-farm:v1.2 \
MSH_SHEPHERD_CUTTLEFISH_IMAGE=rusartur4ik/shepherd-cuttlefish:v1.2 \
scripts/integration/smoke_docker.sh
```

### Console mode (no Docker Compose, isolated temp state)

```bash
./gradlew :manager:service:installDist
scripts/integration/console.sh
```

What it validates:
- manager API session lifecycle (create/release)
- request validation (`deviceType`)
- targeted cuttlefish allocation path (`apiLevel=35`) when cuttlefish inventory is available
- Jenkins shared-library step (`vars/shepherdTest.groovy`) through a local Groovy harness
- no writes to default `~/.msh` state path

### Docker Compose mode (manager + real adapters, locally built images)

```bash
scripts/integration/docker_compose.sh
```

What it validates:
- manager + all real adapters wiring in containers (`shepherd-adb`, `shepherd-farm`, `shepherd-cuttlefish`)
- backend integration for adapter dependencies (`adb` daemon, farm-server contract service, cvdr command contract)
- successful + partial emulator allocations and physical-capacity behavior
- targeted cuttlefish allocation path (`apiLevel=35`) when cuttlefish inventory is available (otherwise explicitly skipped with reason)
- release semantics
- Jenkins shared-library step in containerized topology

### End-to-end APK test (requires connected device)

Full system test: Shepherd allocates a real device, APKs are installed and run **through the session-scoped ADB proxy**, instrumentation passes, session is released. Green = the complete production flow works.

```bash
./gradlew :manager:service:installDist :adapter:shepherd-adb:installDist
scripts/integration/e2e_apk.sh
```

What it validates:
- manager + shepherd-adb start and become healthy
- session allocation returns a valid ADB proxy endpoint
- Shepherd proxy exposes exactly 1 device (lease isolation)
- `adb install` and `am instrument` work through the proxy port
- `android/architecture-samples` instrumentation tests pass on the connected device
- session release clears all active sessions

Requires: physical device or running emulator connected via `adb devices`. API level is auto-detected from the device. Pinned APKs are at `scripts/public_ui/apks/`.

Optional env vars: `ADB_SERIAL` (force a specific device), `MSH_E2E_TTL` (session TTL in seconds, default 300).

### Jenkins shared-library harness only

```bash
MSH_URL=http://localhost:6037 groovy scripts/integration/jenkins_harness.groovy
```

### Public pinned UI sample (direct ADB, no Shepherd)

Runs the same instrumentation suite but connects directly to ADB, bypassing Shepherd. Useful for verifying APKs and device state independently.

```bash
scripts/public_ui/build_test_apk.sh   # build APKs once (clones repo, builds, caches)
scripts/public_ui/run_tests.sh        # install + am instrument directly via adb
```

Pinned repository: `android/architecture-samples` @ `ee66e1526b84c026615df032c705842b7d2a521f`

## Roadmap

Track planned work in GitHub Issues:

- [github.com/rus-artur4ik/marathon-shepherd/issues](https://github.com/rus-artur4ik/marathon-shepherd/issues)

## Project Structure

```
adapter/
  api/          Shared adapter contract, auth, server bootstrap
  shepherd-adb/         Physical-device adapter (shepherd-adb)
  shepherd-farm/        Emulator adapter (shepherd-farm)
  shepherd-cuttlefish/  Cuttlefish/CVDR adapter (shepherd-cuttlefish)
manager/
  service/      REST API, session lifecycle, SQLite state
  cli/          mshctl operator CLI
deploy/         Dockerfiles, msh.yaml.example
vars/           Jenkins Shared Library entry point
scripts/        Health-check helper script
```

**Stack:** Kotlin 2.3 · JVM 21 · Ktor · Exposed · SQLite · kaml · Clikt
