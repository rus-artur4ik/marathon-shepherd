#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/common.sh"
set_log_context "Console Integration"
enable_dynamic_logs

MANAGER_BIN="${REPO_ROOT}/manager/service/build/install/manager/bin/manager"
ADB_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-adb/build/install/shepherd-adb/bin/shepherd-adb"
FARM_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-farm/build/install/shepherd-farm/bin/shepherd-farm"
CUTTLEFISH_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-cuttlefish/build/install/shepherd-cuttlefish/bin/shepherd-cuttlefish"
FARM_SERVER_SCRIPT="${REPO_ROOT}/deploy/integration/farm_server.py"
CVDR_SCRIPT="${REPO_ROOT}/deploy/integration/cvdr"

TMP_ROOT=""
ADB_SERVER_PID=""
FARM_SERVER_PID=""
ADB_ADAPTER_PID=""
FARM_ADAPTER_PID=""
CUTTLEFISH_ADAPTER_PID=""
MANAGER_PID=""
SESSION_ID_EMULATOR=""
SESSION_ID_EMULATOR_PARTIAL=""
SESSION_ID_PHYSICAL=""
SESSION_ID_CUTTLEFISH=""
CUTTLEFISH_AVAILABLE="0"

cleanup() {
    if [[ -n "${SESSION_ID_EMULATOR}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_EMULATOR}" || true
    fi
    if [[ -n "${SESSION_ID_EMULATOR_PARTIAL}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_EMULATOR_PARTIAL}" || true
    fi
    if [[ -n "${SESSION_ID_PHYSICAL}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_PHYSICAL}" || true
    fi
    if [[ -n "${SESSION_ID_CUTTLEFISH}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_CUTTLEFISH}" || true
    fi
    if [[ -n "${MANAGER_PID}" ]]; then
        kill "${MANAGER_PID}" >/dev/null 2>&1 || true
        wait "${MANAGER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${CUTTLEFISH_ADAPTER_PID}" ]]; then
        kill "${CUTTLEFISH_ADAPTER_PID}" >/dev/null 2>&1 || true
        wait "${CUTTLEFISH_ADAPTER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${FARM_ADAPTER_PID}" ]]; then
        kill "${FARM_ADAPTER_PID}" >/dev/null 2>&1 || true
        wait "${FARM_ADAPTER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${ADB_ADAPTER_PID}" ]]; then
        kill "${ADB_ADAPTER_PID}" >/dev/null 2>&1 || true
        wait "${ADB_ADAPTER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${FARM_SERVER_PID}" ]]; then
        kill "${FARM_SERVER_PID}" >/dev/null 2>&1 || true
        wait "${FARM_SERVER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${ADB_SERVER_PID}" ]]; then
        kill "${ADB_SERVER_PID}" >/dev/null 2>&1 || true
        wait "${ADB_SERVER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${TMP_ROOT}" && -d "${TMP_ROOT}" ]]; then
        rm -rf "${TMP_ROOT}"
    fi
}

trap cleanup EXIT

find_free_port() {
    python3 - <<'PY'
import socket

sock = socket.socket()
sock.bind(("127.0.0.1", 0))
print(sock.getsockname()[1])
sock.close()
PY
}

log_step "Checking prerequisites"
require_command python3
require_command curl
require_command adb

if [[ ! -x "${MANAGER_BIN}" ]]; then
    fail "Manager binary is missing. Build once with: ./gradlew :manager:service:installDist"
fi
if [[ ! -x "${ADB_ADAPTER_BIN}" ]]; then
    fail "shepherd-adb binary is missing. Build once with: ./gradlew :adapter:shepherd-adb:installDist"
fi
if [[ ! -x "${FARM_ADAPTER_BIN}" ]]; then
    fail "shepherd-farm binary is missing. Build once with: ./gradlew :adapter:shepherd-farm:installDist"
fi
if [[ ! -x "${CUTTLEFISH_ADAPTER_BIN}" ]]; then
    fail "shepherd-cuttlefish binary is missing. Build once with: ./gradlew :adapter:shepherd-cuttlefish:installDist"
fi
if [[ ! -f "${FARM_SERVER_SCRIPT}" ]]; then
    fail "Farm server integration harness not found: ${FARM_SERVER_SCRIPT}"
fi
if [[ ! -x "${CVDR_SCRIPT}" ]]; then
    fail "CVDR integration harness must be executable: ${CVDR_SCRIPT}"
fi

TMP_ROOT="$(mktemp -d)"
STATE_DIR="${TMP_ROOT}/state"
mkdir -p "${STATE_DIR}"
MANAGER_PORT="$(find_free_port)"
ADB_SERVER_PORT="$(find_free_port)"
ADB_ADAPTER_PORT="$(find_free_port)"
FARM_SERVER_PORT="$(find_free_port)"
FARM_ADAPTER_PORT="$(find_free_port)"
CUTTLEFISH_ADAPTER_PORT="$(find_free_port)"
MANAGER_URL="http://127.0.0.1:${MANAGER_PORT}"
ADB_ADAPTER_URL="http://127.0.0.1:${ADB_ADAPTER_PORT}"
FARM_ADAPTER_URL="http://127.0.0.1:${FARM_ADAPTER_PORT}"
CUTTLEFISH_ADAPTER_URL="http://127.0.0.1:${CUTTLEFISH_ADAPTER_PORT}"
CONFIG_FILE="${TMP_ROOT}/msh.yaml"
CVDR_STATE_FILE="${TMP_ROOT}/cvdr-state.json"
CVDR_LEASES_FILE="${TMP_ROOT}/cvdr-leases.json"

HOME_STATE_BEFORE="missing"
if [[ -d "${HOME}/.msh" ]]; then
    HOME_STATE_BEFORE="$(stat -f "%m" "${HOME}/.msh" 2>/dev/null || echo "existing")"
fi

log_step "Preparing temporary manager config (${CONFIG_FILE})"
printf '%s\n' \
    "providers:" \
    "  - name: \"integration-adb\"" \
    "    url: \"${ADB_ADAPTER_URL}\"" \
    "    secret: \"integration-secret-adb\"" \
    "  - name: \"integration-farm\"" \
    "    url: \"${FARM_ADAPTER_URL}\"" \
    "    secret: \"integration-secret-farm\"" \
    "  - name: \"integration-cuttlefish\"" \
    "    url: \"${CUTTLEFISH_ADAPTER_URL}\"" \
    "    secret: \"integration-secret-cuttlefish\"" \
    > "${CONFIG_FILE}"

log_step "Starting local adb server on port ${ADB_SERVER_PORT}"
ADB_SERVER_PORT="${ADB_SERVER_PORT}" adb -a nodaemon server > "${TMP_ROOT}/adb-server.log" 2>&1 &
ADB_SERVER_PID="$!"
sleep 1

if ! ADB_SERVER_PORT="${ADB_SERVER_PORT}" adb devices >/dev/null 2>&1; then
    cat "${TMP_ROOT}/adb-server.log" || true
    fail "ADB server did not become ready on port ${ADB_SERVER_PORT}"
fi

log_step "Starting farm-server integration harness on port ${FARM_SERVER_PORT}"
FARM_SERVER_PORT="${FARM_SERVER_PORT}" \
FARM_SERVER_TOTAL="3" \
FARM_SERVER_AVAILABLE="3" \
python3 "${FARM_SERVER_SCRIPT}" > "${TMP_ROOT}/farm-server.log" 2>&1 &
FARM_SERVER_PID="$!"
if ! wait_for_http_json "http://127.0.0.1:${FARM_SERVER_PORT}/health" 60; then
    cat "${TMP_ROOT}/farm-server.log" || true
    fail "Farm server integration harness did not become healthy"
fi

log_step "Starting real adapter binaries (adb + farm + cuttlefish)"
ADAPTER_PORT="${ADB_ADAPTER_PORT}" \
ADAPTER_ADB_PORT="${ADB_SERVER_PORT}" \
ADAPTER_SECRET="integration-secret-adb" \
ADB_SERVER_PORT="${ADB_SERVER_PORT}" \
"${ADB_ADAPTER_BIN}" > "${TMP_ROOT}/shepherd-adb.log" 2>&1 &
ADB_ADAPTER_PID="$!"

ADAPTER_PORT="${FARM_ADAPTER_PORT}" \
ADAPTER_ADB_PORT="5037" \
ADAPTER_SECRET="integration-secret-farm" \
FARM_SERVER_HOST="127.0.0.1" \
FARM_SERVER_PORT="${FARM_SERVER_PORT}" \
FARM_SUPPORTED_API_LEVELS="34" \
"${FARM_ADAPTER_BIN}" > "${TMP_ROOT}/shepherd-farm.log" 2>&1 &
FARM_ADAPTER_PID="$!"

ADAPTER_PORT="${CUTTLEFISH_ADAPTER_PORT}" \
ADAPTER_ADB_PORT="6520" \
ADAPTER_SECRET="integration-secret-cuttlefish" \
CVDR_PATH="${CVDR_SCRIPT}" \
CVDR_LEASES_PATH="${CVDR_LEASES_FILE}" \
CVDR_STATE_PATH="${CVDR_STATE_FILE}" \
CVDR_INITIAL_INSTANCES="2" \
"${CUTTLEFISH_ADAPTER_BIN}" > "${TMP_ROOT}/shepherd-cuttlefish.log" 2>&1 &
CUTTLEFISH_ADAPTER_PID="$!"

if ! wait_for_http_json "${ADB_ADAPTER_URL}/health" 60; then
    cat "${TMP_ROOT}/shepherd-adb.log" || true
    fail "shepherd-adb did not become healthy"
fi
if ! wait_for_http_json "${FARM_ADAPTER_URL}/health" 60; then
    cat "${TMP_ROOT}/shepherd-farm.log" || true
    fail "shepherd-farm did not become healthy"
fi
if ! wait_for_http_json "${CUTTLEFISH_ADAPTER_URL}/health" 60; then
    cat "${TMP_ROOT}/shepherd-cuttlefish.log" || true
    fail "shepherd-cuttlefish did not become healthy"
fi

log_step "Starting manager process on port ${MANAGER_PORT}"
MSH_PORT="${MANAGER_PORT}" \
MSH_CONFIG="${CONFIG_FILE}" \
MSH_DATA_DIR="${STATE_DIR}" \
"${MANAGER_BIN}" > "${TMP_ROOT}/manager.log" 2>&1 &
MANAGER_PID="$!"

if ! wait_for_http_json "${MANAGER_URL}/health" 60; then
    cat "${TMP_ROOT}/manager.log" || true
    fail "Manager did not become healthy"
fi
log_success "Manager is healthy"

log_step "Scenario 1: all real adapters are registered and healthy"
http_json "GET" "${MANAGER_URL}/api/v1/devices"
assert_status "200"
assert_json_expr 'all(any(provider.get("name") == provider_name for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-farm", "integration-cuttlefish"])' "All integration providers must be present"
assert_json_expr 'all(any(provider.get("name") == provider_name and provider.get("status") == "HEALTHY" for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-farm", "integration-cuttlefish"])' "All integration providers must be healthy"
assert_json_expr 'any(provider.get("name") == "integration-adb" and "physical" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "ADB adapter should publish physical support"
assert_json_expr 'any(provider.get("name") == "integration-farm" and "emulator" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "Farm adapter should publish emulator support"
assert_json_expr 'any(provider.get("name") == "integration-cuttlefish" and "emulator" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "Cuttlefish adapter should publish emulator support"
CUTTLEFISH_AVAILABLE="$(extract_json_value 'next((int(profile.get("count", 0)) for provider in payload.get("providers", []) if provider.get("name") == "integration-cuttlefish" for profile in provider.get("inventory", []) if profile.get("deviceType") == "emulator"), 0)')"
log_success "All real adapters are healthy and visible in manager inventory"

log_step "Scenario 2: successful emulator allocation"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":2,"apiLevel":"34","ttlSeconds":120,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session should become READY"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one emulator allocation"
SESSION_ID_EMULATOR="$(extract_json_value 'payload.get("id")')"
log_success "Allocated emulator session ${SESSION_ID_EMULATOR}"

log_step "Scenario 3: invalid device type validation"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":1,"apiLevel":"34","ttlSeconds":120,"deviceType":"tablet"}'
assert_status "400"
assert_json_expr '"Unsupported deviceType" in payload.get("error", "")' "Expected 400 validation response"
log_success "Validation works"

log_step "Scenario 4: partial emulator allocation when requested > available"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":8,"apiLevel":"34","ttlSeconds":120,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Partial session should still be READY"
assert_json_expr 'payload.get("requestedDevices") == 8' "Requested devices mismatch"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one emulator device"
assert_json_expr 'payload.get("allocatedDevices", 0) <= payload.get("requestedDevices", 0)' "Allocated devices cannot exceed requested"
SESSION_ID_EMULATOR_PARTIAL="$(extract_json_value 'payload.get("id")')"
log_success "Partially allocated emulator session ${SESSION_ID_EMULATOR_PARTIAL}"

log_step "Scenario 5: physical allocation behavior for ADB adapter"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":1,"apiLevel":"34","ttlSeconds":120,"deviceType":"physical"}'
if [[ "${HTTP_STATUS}" == "201" ]]; then
    assert_json_expr 'payload.get("status") == "READY"' "Physical session should be READY when rack has devices"
    assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one physical device"
    SESSION_ID_PHYSICAL="$(extract_json_value 'payload.get("id")')"
    log_success "Physical allocation succeeded with session ${SESSION_ID_PHYSICAL}"
else
    assert_status "503"
    assert_json_expr '"No devices available" in payload.get("error", "")' "Expected capacity error when no physical devices are connected"
    log_success "Physical allocation correctly failed on empty physical rack"
fi

if (( CUTTLEFISH_AVAILABLE > 0 )); then
    log_step "Scenario 6: targeted cuttlefish allocation (API 35)"
    http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":1,"apiLevel":"35","ttlSeconds":120,"deviceType":"emulator"}'
    assert_status "201"
    assert_json_expr 'payload.get("status") == "READY"' "Cuttlefish session should become READY"
    assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one cuttlefish device"
    assert_json_expr 'any(server.get("port") == 6520 for server in payload.get("adbServers", []))' "Expected cuttlefish ADB endpoint in adbServers"
    SESSION_ID_CUTTLEFISH="$(extract_json_value 'payload.get("id")')"
    log_success "Cuttlefish allocation succeeded with session ${SESSION_ID_CUTTLEFISH}"
else
    log_step "Scenario 6: targeted cuttlefish allocation (skipped, no available instances)"
    log_skip "Cuttlefish inventory count is 0 in this environment"
fi

log_step "Scenario 7: explicit release for created sessions"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_EMULATOR}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Emulator session should be released"
SESSION_ID_EMULATOR=""
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_EMULATOR_PARTIAL}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Partial emulator session should be released"
SESSION_ID_EMULATOR_PARTIAL=""
if [[ -n "${SESSION_ID_PHYSICAL}" ]]; then
    http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_PHYSICAL}"
    assert_status "200"
    assert_json_expr 'payload.get("status") == "released"' "Physical session should be released"
    SESSION_ID_PHYSICAL=""
fi
if [[ -n "${SESSION_ID_CUTTLEFISH}" ]]; then
    http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_CUTTLEFISH}"
    assert_status "200"
    assert_json_expr 'payload.get("status") == "released"' "Cuttlefish session should be released"
    SESSION_ID_CUTTLEFISH=""
fi
log_success "Release endpoints confirmed"

log_step "Scenario 8: no active sessions after cleanup"
http_json "GET" "${MANAGER_URL}/api/v1/sessions"
assert_status "200"
assert_json_expr 'all(session.get("status") in ("RELEASED", "EXPIRED", "FAILED") for session in payload)' "Expected only terminal session statuses"
log_success "No active sessions remain"

log_step "Verifying no default ~/.msh state changes"
HOME_STATE_AFTER="missing"
if [[ -d "${HOME}/.msh" ]]; then
    HOME_STATE_AFTER="$(stat -f "%m" "${HOME}/.msh" 2>/dev/null || echo "existing")"
fi
if [[ "${HOME_STATE_BEFORE}" != "${HOME_STATE_AFTER}" ]]; then
    fail "~/.msh state changed, expected isolated temp state only"
fi
log_success "Default ~/.msh state unchanged"

log_step "All console integration scenarios passed"
log_success "All console integration scenarios passed"
