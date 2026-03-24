#!/usr/bin/env bash
# Full end-to-end test: start Shepherd (manager + shepherd-adb using built
# binaries), allocate a session, install the pinned APKs *through the
# session-scoped ADB proxy*, run instrumentation, assert all tests pass,
# then release the session.
#
# Green = the complete Shepherd flow works: allocation → ADB proxy → device →
#         Android instrumentation tests → session cleanup.
#
# Prerequisites:
#   ./gradlew :manager:service:installDist :adapter:shepherd-adb:installDist
#   An ADB device connected and listed by `adb devices`
#   APKs present: scripts/public_ui/apks/architecture-samples-*/
#
# Optional env vars:
#   ADB_SERIAL          Force a specific device serial
#   MSH_E2E_TTL         Session TTL in seconds (default: 300)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/common.sh"
set_log_context "E2E APK"
enable_dynamic_logs

APK_DIR="${REPO_ROOT}/scripts/public_ui/apks/architecture-samples-ee66e1526b84c026615df032c705842b7d2a521f"
APP_APK="${APK_DIR}/app-debug.apk"
TEST_APK="${APK_DIR}/app-debug-androidTest.apk"
TEST_RUNNER="com.example.android.architecture.blueprints.main.test/com.example.android.architecture.blueprints.todoapp.CustomTestRunner"
APP_PACKAGE="com.example.android.architecture.blueprints.main"
TEST_PACKAGE="com.example.android.architecture.blueprints.main.test"

MANAGER_BIN="${REPO_ROOT}/manager/service/build/install/manager/bin/manager"
ADB_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-adb/build/install/shepherd-adb/bin/shepherd-adb"
SESSION_TTL="${MSH_E2E_TTL:-300}"

TMP_ROOT=""
ADB_ADAPTER_PID=""
MANAGER_PID=""
SESSION_ID=""
MANAGER_URL=""

cleanup() {
    if [[ -n "${SESSION_ID}" && -n "${MANAGER_URL}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}" || true
    fi
    if [[ -n "${MANAGER_PID}" ]]; then
        kill "${MANAGER_PID}" >/dev/null 2>&1 || true
        wait "${MANAGER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${ADB_ADAPTER_PID}" ]]; then
        kill "${ADB_ADAPTER_PID}" >/dev/null 2>&1 || true
        wait "${ADB_ADAPTER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${TMP_ROOT}" && -d "${TMP_ROOT}" ]]; then
        rm -rf "${TMP_ROOT}"
    fi
}
trap cleanup EXIT

find_free_port() {
    python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

# ── Prerequisites ─────────────────────────────────────────────────────────────

log_step "Checking prerequisites"
require_command python3
require_command curl
require_command adb

if [[ ! -x "${MANAGER_BIN}" ]]; then
    fail "Manager binary not found. Run: ./gradlew :manager:service:installDist"
fi
if [[ ! -x "${ADB_ADAPTER_BIN}" ]]; then
    fail "shepherd-adb binary not found. Run: ./gradlew :adapter:shepherd-adb:installDist"
fi
if [[ ! -f "${APP_APK}" ]]; then
    fail "App APK not found: ${APP_APK}"
fi
if [[ ! -f "${TEST_APK}" ]]; then
    fail "Test APK not found: ${TEST_APK}"
fi

# ── Device detection ──────────────────────────────────────────────────────────

log_step "Detecting connected ADB device"
CONNECTED_SERIALS="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [[ -z "${CONNECTED_SERIALS}" ]]; then
    fail "No ADB device connected. Connect a physical device or start an emulator before running this test."
fi

if [[ -n "${ADB_SERIAL:-}" ]]; then
    if ! echo "${CONNECTED_SERIALS}" | grep -qF "${ADB_SERIAL}"; then
        fail "Requested device ${ADB_SERIAL} is not connected. Connected: $(echo "${CONNECTED_SERIALS}" | tr '\n' ' ')"
    fi
    DEVICE_SERIAL="${ADB_SERIAL}"
else
    DEVICE_SERIAL="$(echo "${CONNECTED_SERIALS}" | head -1)"
fi

DEVICE_API="$(adb -s "${DEVICE_SERIAL}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
if [[ -z "${DEVICE_API}" ]]; then
    DEVICE_API="34"
fi
log_success "Device: ${DEVICE_SERIAL} (API ${DEVICE_API})"

# ── Shepherd startup ──────────────────────────────────────────────────────────

TMP_ROOT="$(mktemp -d)"
STATE_DIR="${TMP_ROOT}/state"
mkdir -p "${STATE_DIR}"

MANAGER_PORT="$(find_free_port)"
ADB_ADAPTER_PORT="$(find_free_port)"
PROXY_BASE_PORT="$(find_free_port)"
MANAGER_URL="http://127.0.0.1:${MANAGER_PORT}"
ADB_ADAPTER_URL="http://127.0.0.1:${ADB_ADAPTER_PORT}"
ADAPTER_SECRET="e2e-secret"

printf '%s\n' \
    "providers:" \
    "  - name: \"e2e-adb\"" \
    "    url: \"${ADB_ADAPTER_URL}\"" \
    "    secret: \"${ADAPTER_SECRET}\"" \
    > "${TMP_ROOT}/msh.yaml"

log_step "Starting shepherd-adb (connecting to local ADB server on port 5037)"
ADAPTER_PORT="${ADB_ADAPTER_PORT}" \
ADAPTER_ADB_PORT="5037" \
ADB_PROXY_PORT_RANGE="${PROXY_BASE_PORT}-$((PROXY_BASE_PORT + 9))" \
ADAPTER_SECRET="${ADAPTER_SECRET}" \
ADB_LEASES_PATH="${TMP_ROOT}/adb-leases.json" \
"${ADB_ADAPTER_BIN}" > "${TMP_ROOT}/shepherd-adb.log" 2>&1 &
ADB_ADAPTER_PID="$!"

if ! wait_for_http_json "${ADB_ADAPTER_URL}/health" 60; then
    cat "${TMP_ROOT}/shepherd-adb.log" || true
    fail "shepherd-adb did not become healthy within 60 seconds"
fi
log_success "shepherd-adb healthy at ${ADB_ADAPTER_URL}"

log_step "Starting manager"
MSH_PORT="${MANAGER_PORT}" \
MSH_CONFIG="${TMP_ROOT}/msh.yaml" \
MSH_DATA_DIR="${STATE_DIR}" \
"${MANAGER_BIN}" > "${TMP_ROOT}/manager.log" 2>&1 &
MANAGER_PID="$!"

if ! wait_for_http_json "${MANAGER_URL}/health" 60; then
    cat "${TMP_ROOT}/manager.log" || true
    fail "Manager did not become healthy within 60 seconds"
fi
log_success "Manager healthy at ${MANAGER_URL}"

# ── Session allocation ────────────────────────────────────────────────────────

log_step "Allocating session via Shepherd (deviceType=physical, apiLevel=${DEVICE_API})"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" \
    "{\"devices\":1,\"apiLevel\":\"${DEVICE_API}\",\"ttlSeconds\":${SESSION_TTL},\"deviceType\":\"physical\"}"
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session must be READY"
assert_json_expr 'len(payload.get("adbServers", [])) >= 1' "Session must include at least 1 adbServer"
SESSION_ID="$(extract_json_value 'payload.get("id")')"
PROXY_HOST="$(extract_json_value 'payload.get("adbServers", [{}])[0].get("host", "127.0.0.1")')"
PROXY_PORT="$(extract_json_value 'payload.get("adbServers", [{}])[0].get("port")')"
log_success "Session ${SESSION_ID}: proxy = ${PROXY_HOST}:${PROXY_PORT}"

# ── ADB proxy validation ──────────────────────────────────────────────────────

log_step "Verifying lease isolation: proxy exposes exactly 1 device"
PROXY_DEVICES="$(adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" devices 2>/dev/null \
    | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [[ -z "${PROXY_DEVICES}" ]]; then
    cat "${TMP_ROOT}/shepherd-adb.log" || true
    fail "No devices visible through Shepherd ADB proxy at ${PROXY_HOST}:${PROXY_PORT}"
fi
PROXY_DEVICE_COUNT="$(echo "${PROXY_DEVICES}" | wc -l | tr -d ' ')"
if [[ "${PROXY_DEVICE_COUNT}" != "1" ]]; then
    fail "Expected exactly 1 device through proxy, got ${PROXY_DEVICE_COUNT}: $(echo "${PROXY_DEVICES}" | tr '\n' ' ')"
fi
log_success "Shepherd proxy exposes exactly 1 device — lease isolation confirmed"

# ── APK installation ──────────────────────────────────────────────────────────

log_step "Installing app APK through Shepherd proxy"
adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" install -r "${APP_APK}" \
    > "${TMP_ROOT}/install-app.log" 2>&1
log_success "App APK installed"

log_step "Installing test APK through Shepherd proxy"
adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" install -r -t "${TEST_APK}" \
    > "${TMP_ROOT}/install-test.log" 2>&1
log_success "Test APK installed"

# ── Instrumentation ───────────────────────────────────────────────────────────

log_step "Running instrumentation tests through Shepherd proxy"
set +e
INSTRUMENT_OUTPUT="$(adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" \
    shell am instrument -w "${TEST_RUNNER}" 2>&1)"
INSTRUMENT_EXIT="$?"
set -e

echo "${INSTRUMENT_OUTPUT}"

if echo "${INSTRUMENT_OUTPUT}" | grep -qE "FAILURES!|INSTRUMENTATION_ABORTED|Error in"; then
    fail "Instrumentation tests reported failures"
fi
if ! echo "${INSTRUMENT_OUTPUT}" | grep -qE "^OK \([0-9]+ tests?\)|Tests run:"; then
    fail "Could not confirm test success from instrumentation output (expected 'OK (N tests)' or 'Tests run:')"
fi
log_success "Instrumentation tests passed"

# ── Session release ───────────────────────────────────────────────────────────

log_step "Releasing session via Shepherd"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Session must be released"
SESSION_ID=""
log_success "Session released"

log_step "Verifying no active sessions remain"
http_json "GET" "${MANAGER_URL}/api/v1/sessions"
assert_status "200"
assert_json_expr 'all(s.get("status") in ("RELEASED", "EXPIRED", "FAILED") for s in payload)' \
    "Expected only terminal session statuses"
log_success "No active sessions remain"

# ── APK cleanup ───────────────────────────────────────────────────────────────

log_step "Uninstalling APKs"
adb -s "${DEVICE_SERIAL}" uninstall "${APP_PACKAGE}" >/dev/null 2>&1 || true
adb -s "${DEVICE_SERIAL}" uninstall "${TEST_PACKAGE}" >/dev/null 2>&1 || true
log_success "APKs uninstalled"

log_step "E2E test completed"
log_success "Full Shepherd flow verified: allocation → ADB proxy → lease isolation → instrumentation → cleanup"
