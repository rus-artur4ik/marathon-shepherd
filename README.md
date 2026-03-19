# Android UI Test Farm — Jenkins Shared Library

Jenkins Shared Library that lets any Android project run UI tests on a distributed device farm with a single call:

```groovy
@Library('android-test-farm') _
androidUITests(devices: 3)
```

The library automatically discovers available physical devices and spins up emulators to fill the gap — no manual device management required.

## How It Works

1. Loads the farm topology from `resources/farm-config.yaml`.
2. Connects to all ADB servers on the farm.
3. Counts available physical devices across all hosts.
4. If more devices are needed — acquires emulators from [farm-server](https://github.com/open-tool/mobile-test-platform) instances.
5. Generates (or uses a custom) Marathonfile with the resolved device topology.
6. Runs [Marathon](https://marathonlabs.github.io/marathon/) to execute tests across all devices.
7. Releases emulators and collects JUnit / HTML reports.

## Architecture

```
┌─────────────────────────────────────────────┐
│              Jenkins Pipeline               │
│  androidUITests(devices: 5)                 │
└────────────────┬────────────────────────────┘
                 │
     ┌───────────▼───────────┐
     │   Marathon Shepherd   │
     │  (this repo)          │
     └───────────┬───────────┘
                 │
    ┌────────────┼─────────────┐
    ▼            ▼             ▼
┌────────┐ ┌────────┐  ┌─────────────┐
│emu-host│ │emu-host│  │device-rack-1│
│   1    │ │   2    │  │(physical    │
│        │ │        │  │ devices     │
│farm-   │ │farm-   │  │ only)       │
│server  │ │server  │  └─────────────┘
│+ ADB   │ │+ ADB   │
└────────┘ └────────┘
```

## API

```groovy
androidUITests(
    devices: 3,                     // desired total device count
    appApk: '...debug.apk',        // path to app APK (auto-detected if omitted)
    testApk: '...androidTest.apk', // path to test APK (auto-detected if omitted)
    marathonfile: null,             // custom Marathonfile path (null = use default)
    api: '34',                      // Android API level for emulators
    timeoutSec: 3600,               // timeout for the entire run
)
```

### Device Allocation Logic

| Situation | Behavior |
|---|---|
| Physical ≥ requested | Uses physical only, no emulators |
| Physical < requested | Uses all physical + acquires emulators for the gap |
| Total capacity < requested | Uses everything available, does NOT fail |
| All devices busy | Waits up to `timeoutSec` for devices to free up |
| Zero devices after timeout | Pipeline **fails** |

## Quick Start

### 1. Set Up Jenkins

1. Go to **Manage Jenkins → Configure System → Global Pipeline Libraries**.
2. Add a library named `android-test-farm` pointing to this repository, branch `main`.
3. Install the **Lockable Resources** plugin and register a resource called `physical-android-devices`.
4. Create a Jenkins agent on a machine with network access to all farm hosts.
5. Install on the agent: `farm-cli-client`, `marathon`, `python3`, `pyyaml`, `adb`.

### 2. Set Up Farm Hosts

On each host that will run emulators:

```bash
git clone <this-repo>
cd android-test-farm-lib
sudo deploy/setup-host.sh
```

The script checks KVM, installs Docker and ADB, builds the farm-server image, and starts the service.

For hosts with only physical devices, just ensure:
- ADB is running and listening on all interfaces (`adb -a -P 5037 fork-server server`)
- Devices are connected via USB and authorized

### 3. Edit Farm Config

Update `resources/farm-config.yaml` with your actual host IPs, ports, and capacities.

### 4. Use in a Pipeline

```groovy
@Library('android-test-farm') _

pipeline {
    stages {
        stage('Build') {
            steps { sh './gradlew assembleDebug assembleAndroidTest' }
        }
        stage('UI Tests') {
            steps { androidUITests(devices: 3) }
        }
    }
}
```

## Health Check

Run the health check to verify farm connectivity:

```bash
./scripts/health-check.sh
```

It checks ADB connectivity, farm-server health endpoints, physical device presence, and local tool availability.

## Project Structure

```
android-test-farm-lib/
├── vars/
│   └── androidUITests.groovy        # shared library entry point
├── resources/
│   ├── farm-config.yaml             # farm topology
│   ├── Marathonfile.yaml            # default Marathon config template
│   └── docker-compose.yml           # farm-server deployment
├── deploy/
│   ├── Dockerfile.farm-server       # multi-stage build from mobile-test-platform
│   ├── adb-server.service           # systemd unit for ADB server
│   └── setup-host.sh               # host bootstrap script
├── scripts/
│   └── health-check.sh             # farm connectivity check
└── README.md
```

## Requirements

- **Jenkins**: Lockable Resources plugin, shared library configured
- **Agent**: `farm-cli-client`, `marathon`, `python3 + pyyaml`, `adb`
- **Emulator hosts**: KVM, Docker
- **Physical device hosts**: Linux, ADB, USB-connected devices
