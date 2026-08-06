#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
source "${REPO_ROOT}/tests/helpers/common.sh"
source "${REPO_ROOT}/tests/helpers/stage_runtime.sh"

MANAGER_BIN="${REPO_ROOT}/manager/service/build/install/manager/bin/manager"
ADB_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-adb/build/install/shepherd-adb/bin/shepherd-adb"

EXTERNAL_MANAGER_URL="${MSH_REAL_DEVICE_MANAGER_URL:-${MSH_URL:-}}"
EXTERNAL_ADAPTER_URL="${MSH_REAL_DEVICE_ADAPTER_URL:-}"
MANAGER_URL="${EXTERNAL_MANAGER_URL}"
REQUESTED_DEVICES="${MSH_REAL_DEVICE_REQUESTED_DEVICES:-1}"
API_LEVEL="${MSH_REAL_DEVICE_API_LEVEL:-}"
TTL_SECONDS="${MSH_REAL_DEVICE_TTL_SECONDS:-120}"
DEVICE_TYPE="${MSH_REAL_DEVICE_TYPE:-physical}"
LEASE_ADB_HOST=""
LEASE_ADB_PORT=""

TMP_ROOT=""
STATE_DIR=""
CONFIG_FILE=""
MANAGER_PID=""
ADB_ADAPTER_PID=""
SESSION_ID=""
ADAPTER_URL=""
ADAPTER_SECRET="${MSH_REAL_DEVICE_ADAPTER_SECRET:-integration-secret-real-device}"
ADVERTISED_ADB_HOST="127.0.0.1"
ADVERTISED_ADB_PORT="5037"
TOTAL_TEST_SCENARIOS=7
stage_runtime_init "Real Device Integration" "${TOTAL_TEST_SCENARIOS}"

cleanup() {
    stage_emit_phase "cleanup" 2>/dev/null || true
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

sock = socket.socket()
sock.bind(("127.0.0.1", 0))
print(sock.getsockname()[1])
sock.close()
PY
}

resolve_adb_advertisement() {
    local socket_value="${ADB_SERVER_SOCKET:-}"
    if [[ -z "${socket_value}" ]]; then
        return 0
    fi
    if [[ "${socket_value}" =~ ^tcp:([^:]+):([0-9]+)$ ]]; then
        ADVERTISED_ADB_HOST="${BASH_REMATCH[1]}"
        ADVERTISED_ADB_PORT="${BASH_REMATCH[2]}"
    fi
}

list_connected_physical_devices() {
    adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1 }'
}

wait_for_inventory_match() {
    local timeout_seconds="$1"
    local started_at
    local now=0
    local elapsed=0
    started_at="$(date +%s)"
    while true; do
        http_json "GET" "${MANAGER_URL}/api/v1/devices"
        if [[ "${HTTP_STATUS}" == "200" ]] && PAYLOAD="${HTTP_BODY}" python3 - <<'PY'
import json
import os
import sys

payload = json.loads(os.environ["PAYLOAD"])
providers = payload.get("providers", [])
for provider in providers:
    for profile in provider.get("inventory", []):
        if profile.get("deviceType") == "physical" and int(profile.get("count", 0)) > 0:
            sys.exit(0)
sys.exit(1)
PY
        then
            return 0
        fi
        now="$(date +%s)"
        elapsed=$((now - started_at))
        if (( elapsed >= timeout_seconds )); then
            return 1
        fi
        sleep 0.3
    done
}

adapter_http_json_bearer() {
    local method="$1"
    local url="$2"
    local secret="$3"
    local payload="${4:-}"
    local raw
    if [[ -n "${payload}" ]]; then
        raw="$(tmp="$(mktemp)"; code="$(curl -sS -o "${tmp}" -w '%{http_code}' -X "${method}" -H "Content-Type: application/json" -H "Authorization: Bearer ${secret}" --data-binary "${payload}" "${url}")"; printf '%s\n' "${code}"; cat "${tmp}"; rm -f "${tmp}")"
    else
        raw="$(tmp="$(mktemp)"; code="$(curl -sS -o "${tmp}" -w '%{http_code}' -X "${method}" -H "Authorization: Bearer ${secret}" "${url}")"; printf '%s\n' "${code}"; cat "${tmp}"; rm -f "${tmp}")"
    fi
    HTTP_STATUS="${raw%%$'\n'*}"
    if [[ "${raw}" == *$'\n'* ]]; then
        HTTP_BODY="${raw#*$'\n'}"
    else
        HTTP_BODY=""
    fi
}

discover_adb_adapter_from_manager() {
    if [[ -n "${EXTERNAL_ADAPTER_URL}" ]]; then
        ADAPTER_URL="${EXTERNAL_ADAPTER_URL}"
        return 0
    fi

    http_json "GET" "${MANAGER_URL}/api/v1/devices"
    assert_status "200"
    local provider_name=""
    provider_name="$(extract_json_value "next((provider.get(\"name\", \"\") for provider in payload.get(\"providers\", []) if any(profile.get(\"deviceType\") == \"${DEVICE_TYPE}\" and int(profile.get(\"count\", 0)) > 0 for profile in provider.get(\"inventory\", []))), \"\")")"
    if [[ -z "${provider_name}" ]]; then
        fail "Could not resolve provider name for deviceType=${DEVICE_TYPE} from manager inventory"
    fi

    http_json "GET" "${MANAGER_URL}/api/v1/config"
    assert_status "200"
    ADAPTER_URL="$(extract_json_value "next((provider.get(\"url\", \"\") for provider in payload.get(\"providers\", []) if provider.get(\"name\") == \"${provider_name}\"), \"\")")"
    local discovered_secret=""
    discovered_secret="$(extract_json_value "next((provider.get(\"secret\", \"\") for provider in payload.get(\"providers\", []) if provider.get(\"name\") == \"${provider_name}\"), \"\")")"
    if [[ -z "${ADAPTER_URL}" ]]; then
        fail "Could not resolve adapter URL for provider ${provider_name}"
    fi
    if [[ -n "${discovered_secret}" ]]; then
        ADAPTER_SECRET="${discovered_secret}"
    fi
}

start_self_managed_stack() {
    local manager_port=""
    local adapter_port=""
    local adapter_env=()
    TMP_ROOT="$(mktemp -d)"
    STATE_DIR="${TMP_ROOT}/state"
    mkdir -p "${STATE_DIR}"
    CONFIG_FILE="${TMP_ROOT}/msh.yaml"

    if [[ ! -x "${MANAGER_BIN}" ]]; then
        fail "Manager binary is missing. Build once with: ./gradlew :manager:service:installDist"
    fi
    if [[ ! -x "${ADB_ADAPTER_BIN}" ]]; then
        fail "shepherd-adb binary is missing. Build once with: ./gradlew :adapter:shepherd-adb:installDist"
    fi
    if [[ "${DEVICE_TYPE}" != "physical" ]]; then
        fail "Self-managed real-device integration supports only deviceType=physical. Set MSH_REAL_DEVICE_MANAGER_URL for an external manager."
    fi
    if [[ -z "$(list_connected_physical_devices)" ]]; then
        fail "No connected physical adb devices were found"
    fi

    resolve_adb_advertisement
    manager_port="$(find_free_port)"
    adapter_port="$(find_free_port)"
    ADAPTER_URL="http://127.0.0.1:${adapter_port}"
    MANAGER_URL="http://127.0.0.1:${manager_port}"

    stage_begin_phase "configuring" "Preparing temporary real-device manager config (${CONFIG_FILE})"
    printf '%s\n' \
        "providers:" \
        "  - name: \"integration-real-device-adb\"" \
        "    url: \"${ADAPTER_URL}\"" \
        "    accessHost: \"${ADVERTISED_ADB_HOST}\"" \
        "    secret: \"${ADAPTER_SECRET}\"" \
        > "${CONFIG_FILE}"

    stage_begin_phase "starting" "Starting shepherd-adb process on port ${adapter_port}" 1 2
    adapter_env=(
        "ADAPTER_PORT=${adapter_port}"
        "ADAPTER_ADB_PORT=${ADVERTISED_ADB_PORT}"
        "ADAPTER_SECRET=${ADAPTER_SECRET}"
    )
    if [[ -n "${ADB_SERVER_SOCKET:-}" ]]; then
        adapter_env+=("ADB_SERVER_SOCKET=${ADB_SERVER_SOCKET}")
    fi
    env "${adapter_env[@]}" "${ADB_ADAPTER_BIN}" > "${TMP_ROOT}/shepherd-adb.log" 2>&1 &
    ADB_ADAPTER_PID="$!"

    if ! wait_for_http_json "${ADAPTER_URL}/health" 60; then
        cat "${TMP_ROOT}/shepherd-adb.log" || true
        fail "shepherd-adb did not become healthy"
    fi
    log_success "shepherd-adb is healthy"

    stage_begin_phase "starting" "Starting temporary manager process on port ${manager_port}" 2 2
    env \
        MSH_PORT="${manager_port}" \
        MSH_CONFIG="${CONFIG_FILE}" \
        MSH_DATA_DIR="${STATE_DIR}" \
        "${MANAGER_BIN}" > "${TMP_ROOT}/manager.log" 2>&1 &
    MANAGER_PID="$!"

    if ! wait_for_http_json "${MANAGER_URL}/health" 60; then
        cat "${TMP_ROOT}/manager.log" || true
        fail "Temporary manager did not become healthy"
    fi
    log_success "Temporary real-device manager is healthy"
    stage_begin_phase "starting" "Waiting for physical inventory to appear in manager"
    if ! wait_for_inventory_match 30; then
        http_json "GET" "${MANAGER_URL}/api/v1/devices" || true
        echo "JSON payload:"
        echo "${HTTP_BODY}"
        fail "Physical inventory did not appear in manager in time"
    fi
    log_success "Physical inventory is visible in manager"
}

if [[ ! "${REQUESTED_DEVICES}" =~ ^[1-9][0-9]*$ ]]; then
    fail "MSH_REAL_DEVICE_REQUESTED_DEVICES must be a positive integer"
fi
if [[ -n "${API_LEVEL}" ]] && [[ ! "${API_LEVEL}" =~ ^[1-9][0-9]*$ ]]; then
    fail "MSH_REAL_DEVICE_API_LEVEL must be a positive integer when provided"
fi
if [[ ! "${TTL_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
    fail "MSH_REAL_DEVICE_TTL_SECONDS must be a positive integer"
fi
if [[ "${DEVICE_TYPE}" != "physical" && "${DEVICE_TYPE}" != "emulator" ]]; then
    fail "MSH_REAL_DEVICE_TYPE must be either 'physical' or 'emulator'"
fi

stage_begin_phase "preparing" "Checking prerequisites"
require_command curl
require_command python3
require_command adb

if [[ -n "${EXTERNAL_MANAGER_URL}" ]]; then
    MANAGER_URL="${EXTERNAL_MANAGER_URL}"
    stage_begin_phase "starting" "Waiting for external manager readiness at ${MANAGER_URL}"
    if ! wait_for_http_json "${MANAGER_URL}/health" 60; then
        fail "Manager did not become healthy in time. Set MSH_REAL_DEVICE_MANAGER_URL correctly or skip with --skip-real-device-integration."
    fi
    log_success "External manager is healthy"
else
    start_self_managed_stack
fi

stage_begin_test "Scenario 1: provider inventory exposes requested device type (${DEVICE_TYPE})"
http_json "GET" "${MANAGER_URL}/api/v1/devices"
assert_status "200"
assert_json_expr 'isinstance(payload.get("providers"), list)' "Expected providers list in /api/v1/devices response"
assert_json_expr "any(profile.get(\"deviceType\") == \"${DEVICE_TYPE}\" and int(profile.get(\"count\", 0)) > 0 for provider in payload.get(\"providers\", []) for profile in provider.get(\"inventory\", []))" "No inventory for requested device type"
if [[ -z "${API_LEVEL}" ]]; then
    API_LEVEL="$(extract_json_value "next((str(profile.get(\"apiLevel\")) for provider in payload.get(\"providers\", []) for profile in provider.get(\"inventory\", []) if profile.get(\"deviceType\") == \"${DEVICE_TYPE}\" and int(profile.get(\"count\", 0)) > 0 and profile.get(\"apiLevel\") is not None), \"\")")"
    if [[ -z "${API_LEVEL}" ]]; then
        fail "Could not resolve api selector automatically from provider inventory"
    fi
fi
log_success "Inventory contains requested device type"
log_info "Using api selector ${API_LEVEL} for session request"
discover_adb_adapter_from_manager

stage_begin_test "Scenario 2: allocate and release a real-device session"
REQUEST_PAYLOAD="$(python3 - <<PY
import json
print(json.dumps({
    "maxDevices": int("${REQUESTED_DEVICES}"),
    "api": "${API_LEVEL}",
    "ttlSeconds": int("${TTL_SECONDS}"),
    "deviceType": "${DEVICE_TYPE}",
}))
PY
)"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" "${REQUEST_PAYLOAD}"
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session must become READY"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one allocated device"
SESSION_ID="$(extract_json_value 'payload.get("id")')"
LEASE_ADB_HOST="$(extract_json_value 'next((server.get("host", "") for server in payload.get("adbServers", []) if server.get("host")), "")')"
LEASE_ADB_PORT="$(extract_json_value 'next((str(server.get("port")) for server in payload.get("adbServers", []) if server.get("port") is not None), "")')"
if [[ -z "${SESSION_ID}" ]]; then
    fail "Session id is missing in create-session response"
fi

stage_begin_test "Scenario 3: lease-scoped adb endpoint exposes only allocated serial"
if [[ -z "${LEASE_ADB_HOST}" || -z "${LEASE_ADB_PORT}" ]]; then
    fail "Lease-scoped adb server coordinates are missing in create-session response"
fi
ADB_PROXY_OUTPUT="$(adb -H "${LEASE_ADB_HOST}" -P "${LEASE_ADB_PORT}" devices)"
ALLOCATED_PROXY_SERIALS="$(printf '%s\n' "${ADB_PROXY_OUTPUT}" | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [[ -z "${ALLOCATED_PROXY_SERIALS}" ]]; then
    echo "${ADB_PROXY_OUTPUT}"
    fail "Lease-scoped adb endpoint did not expose any device"
fi
if [[ "$(printf '%s\n' "${ALLOCATED_PROXY_SERIALS}" | wc -l | tr -d ' ')" != "1" ]]; then
    echo "${ADB_PROXY_OUTPUT}"
    fail "Lease-scoped adb endpoint exposed more than one device"
fi
log_success "Lease-scoped adb endpoint exposes only the allocated device"

stage_begin_test "Scenario 4: adb-reload is blocked while the lease is active"
adapter_http_json_bearer "POST" "${ADAPTER_URL}/adb-reload" "${ADAPTER_SECRET}"
assert_status "409"
assert_json_expr 'payload.get("activeLeaseCount", 0) >= 1' "adb-reload should be blocked while a lease is active"
log_success "adb-reload is blocked while the session holds an active lease"

stage_begin_test "Scenario 5: release the real-device session"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Session should be released"
SESSION_ID=""
log_success "Initial allocation and release completed"

stage_begin_test "Scenario 6: adb-reload succeeds after release"
adapter_http_json_bearer "POST" "${ADAPTER_URL}/adb-reload" "${ADAPTER_SECRET}"
assert_status "200"
assert_json_expr 'payload.get("metadata", {}).get("adbReload") == "ok"' "adb-reload should refresh adapter status after release"
log_success "adb-reload succeeded after all leases were released"

stage_begin_test "Scenario 7: allocation works again after adb-reload"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" "${REQUEST_PAYLOAD}"
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session must become READY after adb-reload"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one allocated device after adb-reload"
SESSION_ID="$(extract_json_value 'payload.get("id")')"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Post-reload session should be released"
SESSION_ID=""
log_success "Allocation still works after adb-reload"

log_step "All real-device integration scenarios passed"
log_success "All real-device integration scenarios passed"
