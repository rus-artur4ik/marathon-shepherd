#!/usr/bin/env groovy

/**
 * androidUITests — entry point for the android-test-farm Jenkins Shared Library.
 *
 * Usage:
 *   androidUITests(devices: 3)
 *
 * The step automatically discovers physical devices, acquires emulators when
 * needed, runs Marathon, and cleans up afterwards.
 */
def call(Map params = [:]) {
    int requestedDevices  = params.get('devices', 1)
    String appApk         = params.get('appApk', '')
    String testApk        = params.get('testApk', '')
    String marathonfile   = params.get('marathonfile', null)
    String api            = params.get('api', '34')
    int timeoutSec        = params.get('timeoutSec', 3600)

    // ── 1. Load farm topology ────────────────────────────────────────────
    def farmConfigText = libraryResource('farm-config.yaml')
    def farmConfig     = readYaml(text: farmConfigText)
    def hosts          = farmConfig.hosts

    echo "=== Android Test Farm ==="
    echo "Requested devices : ${requestedDevices}"
    echo "Farm hosts        : ${hosts.collect { it.name }.join(', ')}"

    // ── 2. Resolve APK paths (defaults = standard Gradle output) ─────────
    if (!appApk) {
        appApk = sh(script: "find . -path '*/build/outputs/apk/debug/*.apk' ! -name '*androidTest*' | head -1",
                     returnStdout: true).trim()
    }
    if (!testApk) {
        testApk = sh(script: "find . -path '*/build/outputs/apk/androidTest/debug/*.apk' | head -1",
                      returnStdout: true).trim()
    }
    if (!appApk || !testApk) {
        error "Could not resolve APK paths. Provide appApk / testApk or run assembleDebug + assembleAndroidTest first."
    }
    echo "App APK  : ${appApk}"
    echo "Test APK : ${testApk}"

    // ── 3. Connect to all ADB servers ────────────────────────────────────
    for (host in hosts) {
        sh "adb connect ${host.adb_host}:${host.adb_port} || true"
        sleep 2
    }

    // ── 4. Collect ADB server addresses for Marathon ─────────────────────
    def adbServers = hosts.collect { [host: it.adb_host, port: it.adb_port] }

    // ── Helper closures ──────────────────────────────────────────────────
    def countPhysicalDevices = {
        // Devices whose serial does NOT look like an emulator (emulator-55xx)
        def output = sh(script: "adb devices | awk '/^[^L].*device\$/ && !/^emulator-/' | wc -l",
                        returnStdout: true).trim()
        return output.toInteger()
    }

    def farmServerHosts = hosts.findAll { it.farm_server }

    def acquireEmulators = { int count, int timeout ->
        if (count <= 0 || farmServerHosts.isEmpty()) return 0

        int acquired = 0
        int remaining = count
        for (host in farmServerHosts) {
            if (remaining <= 0) break
            int canProvide = Math.min(remaining, host.farm_server.max_emulators as int)
            echo "Requesting ${canProvide} emulator(s) from ${host.name} (${host.farm_server.url}) ..."
            def rc = sh(script: """
                farm-cli-client \
                    --server '${host.farm_server.url}' \
                    --command ACQUIRE \
                    --amount ${canProvide} \
                    --adb_host '${host.adb_host}' \
                    --adb_port ${host.adb_port} \
                    --api_level ${api} \
                    --device_connection_timeout_sec ${timeout}
            """, returnStatus: true)
            if (rc == 0) {
                acquired += canProvide
                remaining -= canProvide
            } else {
                echo "WARNING: farm-server on ${host.name} could not provide ${canProvide} emulator(s)."
            }
        }
        return acquired
    }

    def releaseAllEmulators = {
        for (host in farmServerHosts) {
            sh(script: """
                farm-cli-client \
                    --server '${host.farm_server.url}' \
                    --command RELEASE || true
            """, returnStatus: true)
        }
    }

    // ── 5. Device allocation with lock ───────────────────────────────────
    int totalDevices = 0
    int physicalCount = 0
    int emulatorCount = 0

    try {
        timeout(time: timeoutSec, unit: 'SECONDS') {

            // Lock physical devices to prevent parallel access
            lock(resource: 'physical-android-devices') {

                // 5a. Count available physical devices
                physicalCount = countPhysicalDevices()
                echo "Physical devices available: ${physicalCount}"

                int emulatorsNeeded = 0
                if (physicalCount >= requestedDevices) {
                    // Enough physical devices — no emulators required
                    totalDevices = requestedDevices
                } else {
                    // Use all physical + request emulators for the gap
                    emulatorsNeeded = requestedDevices - physicalCount
                    echo "Need ${emulatorsNeeded} emulator(s) to complement ${physicalCount} physical device(s)."

                    emulatorCount = acquireEmulators(emulatorsNeeded, timeoutSec)
                    totalDevices = physicalCount + emulatorCount
                }

                echo "=== Allocation result: ${totalDevices} device(s) (${physicalCount} physical + ${emulatorCount} emulators) ==="

                if (totalDevices == 0) {
                    error "No devices available. Aborting pipeline."
                }

                if (totalDevices < requestedDevices) {
                    echo "WARNING: Running on ${totalDevices} device(s) instead of the requested ${requestedDevices}."
                }

                // ── 6. Prepare Marathonfile ──────────────────────────────
                String marathonfilePath
                if (marathonfile) {
                    marathonfilePath = marathonfile
                    echo "Using user-provided Marathonfile: ${marathonfilePath}"
                } else {
                    // Load default template and substitute placeholders
                    def tpl = libraryResource('Marathonfile.yaml')
                    tpl = tpl.replace('APP_APK_PLACEHOLDER', appApk)
                    tpl = tpl.replace('TEST_APK_PLACEHOLDER', testApk)

                    // Append adbServers section
                    def sb = new StringBuilder(tpl)
                    sb.append('\nadbServers:\n')
                    for (srv in adbServers) {
                        sb.append("  - host: \"${srv.host}\"\n")
                        sb.append("    port: ${srv.port}\n")
                    }

                    marathonfilePath = "${env.WORKSPACE}/Marathonfile-generated.yaml"
                    writeFile(file: marathonfilePath, text: sb.toString())
                    echo "Generated Marathonfile at ${marathonfilePath}"
                }

                // ── 7. Run Marathon ──────────────────────────────────────
                echo "=== Running Marathon ==="
                sh "marathon --marathonfile '${marathonfilePath}'"
            }
        }
    } catch (err) {
        echo "ERROR: ${err.message}"
        throw err
    } finally {
        // ── 8. Release emulators ─────────────────────────────────────────
        echo "=== Releasing emulators ==="
        releaseAllEmulators()

        // ── 9. Collect artifacts ─────────────────────────────────────────
        echo "=== Collecting artifacts ==="
        junit testResults: 'marathon-report/**/junit-reports/*.xml', allowEmptyResults: true
        archiveArtifacts artifacts: 'marathon-report/**', allowEmptyArchive: true
    }
}
