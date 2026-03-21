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
 ┌─────────────┐  ┌───────────────┐  ┌───────────────────┐
 │ Device host │  │ Emulator host │  │  Cuttlefish host  │
 │             │  │               │  │                   │
 │ shepherd-adb│  │ shepherd-farm │  │  shepherd-cfish   │
 │   :7037     │  │   :7037       │  │    :7037          │
 │             │  │               │  │                   │
 │ adb-server  │  │ farm-server   │  │  cvdr             │
 │   :5037     │  │   :5037       │  │  instances :6520  │
 │ USB devices │  │  emulators    │  │  (KVM required)   │
 └──────┬──────┘  └──────┬────────┘  └──────────┬────────┘
        │ ADB :5037      │ ADB :5037           │ ADB :6520
        └────────────────┴─────────────────────┘
              marathon runner connects here
```

**Session lifecycle:** `create → PENDING → acquire leases from providers → READY`. On release or TTL expiry, all leases are rolled back automatically. The manager returns `adbServers` — your CI layer constructs the Marathonfile.

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
    secret: "change-me"   # must match ADAPTER_SECRET on that host
```

**2. Create `docker-compose.yml`:**

```yaml
services:
  manager:
    image: YOUR_USER/shepherd-manager:latest
    container_name: shepherd-manager
    restart: unless-stopped
    volumes:
      - "./msh.yaml:/etc/msh/msh.yaml:ro"
      - msh-data:/var/lib/msh
    ports:
      - "6037:6037"
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:6037/health"]
      interval: 30s
      timeout: 5s
      retries: 3

volumes:
  msh-data:
```

**3. Start:**

```bash
docker compose up -d
curl -sf http://localhost:6037/health
```

Reload config at runtime without restart:

```bash
curl -X POST http://localhost:6037/api/v1/config/reload
```

### Adapters (one per device host)

Each adapter runs on its own host alongside the devices it manages. Pick the right type for your hardware.

**Physical devices (shepherd-adb)**

**1. Create `docker-compose.yml` on the device host:**

```yaml
networks:
  shepherd:
    driver: bridge

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
      - "5037:5037"

  shepherd-adb:
    image: YOUR_USER/shepherd-adb:latest
    container_name: shepherd-adb
    networks:
      - shepherd
    restart: unless-stopped
    environment:
      ADAPTER_ADB_HOST: "192.168.1.10"   # this host's LAN IP, reachable by marathon
      ADAPTER_SECRET: "change-me"        # must match secret in msh.yaml
    ports:
      - "7037:7037"
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
      - "5037:5037"
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/health"]
      interval: 30s
      retries: 5
      start_period: 60s

  shepherd-farm:
    image: YOUR_USER/shepherd-farm:latest
    container_name: shepherd-farm
    networks:
      - shepherd
    restart: unless-stopped
    environment:
      FARM_SERVER_HOST: "farm-server"
      ADAPTER_ADB_HOST: "192.168.1.20"   # this host's LAN IP
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

**1. Create `docker-compose.yml` on the Cuttlefish host:**

```yaml
services:
  shepherd-cuttlefish:
    image: YOUR_USER/shepherd-cuttlefish:latest
    container_name: shepherd-cuttlefish
    privileged: true
    devices:
      - /dev/kvm
    volumes:
      - /usr/local/bin/cvdr:/usr/local/bin/cvdr:ro
      - cuttlefish-home:/home/vsoc-01
    restart: unless-stopped
    environment:
      ADAPTER_ADB_HOST: "192.168.1.30"   # this host's LAN IP
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
| `GET` | `/health` | Manager + provider health |
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
  "adbServers": [{ "host": "192.168.1.10", "port": 5037 }],
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
| API-level targeting | ✓ (homogeneous racks for physical, selective for farm/cuttlefish) |
| Filter by device type per session | ✓ (`deviceType: "physical"` / `"emulator"`) |

## Configuration

### `msh.yaml`

```yaml
providers:
  - name: "device-rack-1"
    url: "http://192.168.1.10:7037"
    secret: "strong-random-secret"         # openssl rand -hex 32

  - name: "emu-farm-1"
    url: "http://192.168.1.20:7037"
    secret: "another-secret"
```

### Manager

| Variable / Flag | Default | Description |
|-----------------|---------|-------------|
| `--config <path>` / `MSH_CONFIG` | `msh.yaml` in CWD | Config file path |
| `MSH_PORT` | `6037` | HTTP port |
| `MSH_DATA_DIR` | `~/.msh` | SQLite state directory |

### Adapter environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ADAPTER_PORT` | `7037` | HTTP port |
| `ADAPTER_ADB_HOST` | **required** | ADB host published to Marathon |
| `ADAPTER_ADB_PORT` | `5037` / `6520` | ADB port published to Marathon |
| `ADAPTER_SECRET` | _(blank = no auth)_ | Bearer token; blank for local dev only |

| Variable | Adapter | Default | Description |
|----------|---------|---------|-------------|
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
| `401` from adapter | `msh.yaml` secret vs `ADAPTER_SECRET` on the adapter host |
| Provider unhealthy, manager healthy | Adapter logs, adapter `/health`, firewall |
| Session `FAILED`, 0 devices | `mshctl devices` — pool available? API level match? |
| Physical rack never satisfies `apiLevel` | All devices on the rack must share the same API level |
| Marathon can't reach devices | ADB port (`5037`/`6520`) must be reachable from the marathon runner |

## Building

```bash
./gradlew test
./gradlew build

./gradlew :manager:service:installDist   # run locally
./gradlew :manager:cli:installDist       # mshctl

docker build -f deploy/Dockerfile                    -t shepherd-manager .
docker build -f deploy/Dockerfile.adapter-adb        -t shepherd-adb .
docker build -f deploy/Dockerfile.adapter-farm       -t shepherd-farm .
docker build -f deploy/Dockerfile.adapter-cuttlefish -t shepherd-cuttlefish .
```

## Project Structure

```
adapter/
  api/          Shared adapter contract, auth, server bootstrap
  adb/          Physical-device adapter (shepherd-adb)
  farm-server/  Emulator adapter (shepherd-farm)
  cuttlefish/   Cuttlefish/CVDR adapter (shepherd-cuttlefish)
manager/
  service/      REST API, session lifecycle, SQLite state
  cli/          mshctl operator CLI
deploy/         Dockerfiles, msh.yaml.example
vars/           Jenkins Shared Library entry point
scripts/        Health-check helper script
```

**Stack:** Kotlin 2.3 · JVM 21 · Ktor · Exposed · SQLite · kaml · Clikt
