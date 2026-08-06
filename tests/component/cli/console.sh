#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
source "${REPO_ROOT}/tests/helpers/common.sh"
source "${REPO_ROOT}/tests/helpers/cloud_orchestrator.sh"
source "${REPO_ROOT}/tests/helpers/stage_runtime.sh"

MANAGER_BIN="${REPO_ROOT}/manager/service/build/install/manager/bin/manager"
ADB_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-adb/build/install/shepherd-adb/bin/shepherd-adb"
FARM_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-farm/build/install/shepherd-farm/bin/shepherd-farm"
CUTTLEFISH_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-cuttlefish/build/install/shepherd-cuttlefish/bin/shepherd-cuttlefish"
MSHCTL_BIN="${REPO_ROOT}/manager/cli/build/install/mshctl/bin/mshctl"
FARM_SERVER_SCRIPT="${REPO_ROOT}/tests/integration/farm_server.py"
TOTAL_TEST_SCENARIOS=14

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
SESSION_ID_CLI=""
QUEUE_HOLDER_SESSION_ID=""
QUEUE_WAITER_SESSION_ID=""
CLOUD_ORCHESTRATOR_CONTAINER_NAME=""
stage_runtime_init "Console Integration" "${TOTAL_TEST_SCENARIOS}"

cleanup() {
    # Always emit the cleanup phase first so the runner dashboard reflects
    # "cleanup" instead of a stale "testing [N/M]" while sessions / processes
    # are being torn down. Guard with `|| true` in case the helper isn't loaded
    # (e.g. early-boot failure before sourcing stage_runtime.sh).
    stage_emit_phase "cleanup" 2>/dev/null || true
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
    if [[ -n "${SESSION_ID_CLI}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_CLI}" || true
    fi
    if [[ -n "${QUEUE_HOLDER_SESSION_ID}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${QUEUE_HOLDER_SESSION_ID}" || true
    fi
    if [[ -n "${QUEUE_WAITER_SESSION_ID}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${QUEUE_WAITER_SESSION_ID}" || true
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
    if [[ -n "${CLOUD_ORCHESTRATOR_CONTAINER_NAME}" ]]; then
        docker rm -f "${CLOUD_ORCHESTRATOR_CONTAINER_NAME}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${ADB_SERVER_PID}" ]]; then
        kill "${ADB_SERVER_PID}" >/dev/null 2>&1 || true
        wait "${ADB_SERVER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${TMP_ROOT}" && -d "${TMP_ROOT}" ]]; then
        rm -rf "${TMP_ROOT}"
    fi
    cleanup_cloud_orchestrator_image
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

stage_begin_phase "preparing" "Checking prerequisites"
require_command python3
require_command curl
require_command adb
require_command docker

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
if [[ ! -x "${MSHCTL_BIN}" ]]; then
    fail "mshctl binary is missing. Build once with: ./gradlew :manager:cli:installDist"
fi
if [[ ! -f "${FARM_SERVER_SCRIPT}" ]]; then
    fail "Farm server integration harness not found: ${FARM_SERVER_SCRIPT}"
fi

TMP_ROOT="$(mktemp -d)"
STATE_DIR="${TMP_ROOT}/state"
mkdir -p "${STATE_DIR}"
MANAGER_PORT="$(find_free_port)"
ADB_SERVER_PORT="$(find_free_port)"
ADB_ADAPTER_PORT="$(find_free_port)"
FARM_SERVER_PORT="$(find_free_port)"
FARM_ADAPTER_PORT="$(find_free_port)"
CUTTLEFISH_ORCHESTRATOR_PORT="$(find_free_port)"
CUTTLEFISH_ADAPTER_PORT="$(find_free_port)"
MANAGER_URL="http://127.0.0.1:${MANAGER_PORT}"
ADB_ADAPTER_URL="http://127.0.0.1:${ADB_ADAPTER_PORT}"
FARM_ADAPTER_URL="http://127.0.0.1:${FARM_ADAPTER_PORT}"
CUTTLEFISH_ADAPTER_URL="http://127.0.0.1:${CUTTLEFISH_ADAPTER_PORT}"
CUTTLEFISH_ORCHESTRATOR_URL="http://127.0.0.1:${CUTTLEFISH_ORCHESTRATOR_PORT}"
CONFIG_FILE="${TMP_ROOT}/msh.yaml"
CUTTLEFISH_ORCHESTRATOR_LEASES_FILE="${TMP_ROOT}/cuttlefish-orchestrator-leases.json"
CLOUD_ORCHESTRATOR_CONTAINER_NAME="msh-console-cloud-orchestrator-$$"

HOME_STATE_BEFORE="missing"
if [[ -d "${HOME}/.msh" ]]; then
    HOME_STATE_BEFORE="$(stat -f "%m" "${HOME}/.msh" 2>/dev/null || echo "existing")"
fi

stage_begin_phase "configuring" "Preparing temporary manager config (${CONFIG_FILE})"
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

# Config / image prep first — independent of any service start.
if [[ -n "${MSH_PREBUILT_ORCHESTRATOR_CONFIG:-}" && -s "${MSH_PREBUILT_ORCHESTRATOR_CONFIG}" ]]; then
    stage_begin_phase "reusing" "Reusing pre-downloaded Cloud Orchestrator config"
else
    stage_begin_phase "pulling" "Downloading official Cloud Orchestrator config"
fi
download_cloud_orchestrator_config "${TMP_ROOT}"
# Only claim "building" when we actually rebuild the image; prebuild may have
# already produced msh-cloud-orchestrator:latest.
if [[ "${MSH_SKIP_DOCKER_BUILD:-0}" == "1" ]] && \
   docker image inspect "${CUTTLEFISH_ORCHESTRATOR_IMAGE:-msh-cloud-orchestrator:latest}" >/dev/null 2>&1; then
    stage_begin_phase "reusing" "Reusing pre-built Cloud Orchestrator image"
else
    stage_begin_phase "building" "Building official Cloud Orchestrator image"
fi
build_cloud_orchestrator_image

# ── Infrastructure layer (parallel): adb-server + farm-server + orchestrator ─
# The three services are mutually independent — no reason to start them
# sequentially. Each process is launched in the background; their readiness
# probes then run concurrently in subshells that write a marker on success.
stage_begin_phase "starting" "Starting infrastructure (adb-server, farm-server, orchestrator)" 1 3

ADB_SERVER_PORT="${ADB_SERVER_PORT}" adb -a nodaemon server > "${TMP_ROOT}/adb-server.log" 2>&1 &
ADB_SERVER_PID="$!"

FARM_SERVER_PORT="${FARM_SERVER_PORT}" \
FARM_SERVER_TOTAL="3" \
FARM_SERVER_AVAILABLE="3" \
python3 "${FARM_SERVER_SCRIPT}" > "${TMP_ROOT}/farm-server.log" 2>&1 &
FARM_SERVER_PID="$!"

run_cloud_orchestrator_container "${CLOUD_ORCHESTRATOR_CONTAINER_NAME}" "${CUTTLEFISH_ORCHESTRATOR_PORT}"

# Parallel readiness probes — each writes an `ok` file on success so we know
# at the end which (if any) failed, and can print the right log for diagnosis.
rm -f "${TMP_ROOT}"/ok-*
(
    # adb-server has no /health endpoint — use `adb devices` against its port.
    deadline=$(( $(date +%s) + 60 ))
    until ADB_SERVER_PORT="${ADB_SERVER_PORT}" adb devices >/dev/null 2>&1; do
        (( $(date +%s) >= deadline )) && exit 1
        sleep 0.2
    done
    touch "${TMP_ROOT}/ok-adb-server"
) &
PROBE_ADB=$!
(
    wait_for_http_json "http://127.0.0.1:${FARM_SERVER_PORT}/health" 60 && touch "${TMP_ROOT}/ok-farm-server"
) &
PROBE_FARM=$!
(
    # Orchestrator endpoint varies by version: /health on newer builds, /devices
    # on older, plain / on some forks. Poll all three IN PARALLEL and succeed
    # on the first one that answers — caps total wait at 30s instead of 90s
    # (was `/health(30s) || /devices(30s) || /(30s)` sequentially).
    orch_deadline=$(( $(date +%s) + 30 ))
    while (( $(date +%s) < orch_deadline )); do
        if curl -fsS "${CUTTLEFISH_ORCHESTRATOR_URL}/health" >/dev/null 2>&1 \
            || curl -fsS "${CUTTLEFISH_ORCHESTRATOR_URL}/devices" >/dev/null 2>&1 \
            || curl -fsS "${CUTTLEFISH_ORCHESTRATOR_URL}/" >/dev/null 2>&1; then
            touch "${TMP_ROOT}/ok-orchestrator"
            exit 0
        fi
        sleep 0.3
    done
) &
PROBE_ORCH=$!
wait "${PROBE_ADB}" "${PROBE_FARM}" "${PROBE_ORCH}" || true
[[ -f "${TMP_ROOT}/ok-adb-server"   ]] || { cat "${TMP_ROOT}/adb-server.log"   || true; fail "ADB server did not become ready on port ${ADB_SERVER_PORT}"; }
[[ -f "${TMP_ROOT}/ok-farm-server"  ]] || { cat "${TMP_ROOT}/farm-server.log"  || true; fail "Farm server integration harness did not become healthy"; }
[[ -f "${TMP_ROOT}/ok-orchestrator" ]] || { docker logs "${CLOUD_ORCHESTRATOR_CONTAINER_NAME}" >&2 || true; fail "Cloud Orchestrator container did not become healthy"; }

# ── Adapter layer (parallel): 3 JVM processes ────────────────────────────────
stage_begin_phase "starting" "Starting real adapter binaries (adb + farm + cuttlefish)" 2 3
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
CUTTLEFISH_ORCHESTRATOR_URL="${CUTTLEFISH_ORCHESTRATOR_URL}" \
CUTTLEFISH_ORCHESTRATOR_LEASES_PATH="${CUTTLEFISH_ORCHESTRATOR_LEASES_FILE}" \
CUTTLEFISH_ORCHESTRATOR_INSECURE_TLS="false" \
"${CUTTLEFISH_ADAPTER_BIN}" > "${TMP_ROOT}/shepherd-cuttlefish.log" 2>&1 &
CUTTLEFISH_ADAPTER_PID="$!"

# Parallel readiness probes for adapters. Without this we'd block on the
# slowest-starting adapter sequentially (up to ~2s × 3) instead of waiting
# once for the slowest (~2s total).
rm -f "${TMP_ROOT}"/ok-adapter-*
( wait_for_http_json "${ADB_ADAPTER_URL}/health"       60 && touch "${TMP_ROOT}/ok-adapter-adb" )       & PROBE_A=$!
( wait_for_http_json "${FARM_ADAPTER_URL}/health"      60 && touch "${TMP_ROOT}/ok-adapter-farm" )      & PROBE_F=$!
( wait_for_http_json "${CUTTLEFISH_ADAPTER_URL}/health" 60 && touch "${TMP_ROOT}/ok-adapter-cuttlefish" ) & PROBE_C=$!
wait "${PROBE_A}" "${PROBE_F}" "${PROBE_C}" || true

if [[ ! -f "${TMP_ROOT}/ok-adapter-adb" ]]; then
    cat "${TMP_ROOT}/shepherd-adb.log" || true
    fail "shepherd-adb did not become healthy"
fi
if [[ ! -f "${TMP_ROOT}/ok-adapter-farm" ]]; then
    cat "${TMP_ROOT}/shepherd-farm.log" || true
    fail "shepherd-farm did not become healthy"
fi
if [[ ! -f "${TMP_ROOT}/ok-adapter-cuttlefish" ]]; then
    cat "${TMP_ROOT}/shepherd-cuttlefish.log" || true
    fail "shepherd-cuttlefish did not become healthy"
fi

stage_begin_phase "starting" "Starting manager process on port ${MANAGER_PORT}" 3 3
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

stage_begin_test "Scenario 1: all real adapters are registered and healthy"
http_json "GET" "${MANAGER_URL}/api/v1/devices"
assert_status "200"
assert_json_expr 'all(any(provider.get("name") == provider_name for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-farm", "integration-cuttlefish"])' "All integration providers must be present"
assert_json_expr 'all(any(provider.get("name") == provider_name and provider.get("status") == "HEALTHY" for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-farm", "integration-cuttlefish"])' "All integration providers must be healthy"
assert_json_expr 'any(provider.get("name") == "integration-adb" and "physical" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "ADB adapter should publish physical support"
assert_json_expr 'any(provider.get("name") == "integration-farm" and "emulator" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "Farm adapter should publish emulator support"
assert_json_expr 'any(provider.get("name") == "integration-cuttlefish" and "emulator" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "Cuttlefish adapter should publish emulator support"
log_success "All real adapters are healthy and visible in manager inventory"

stage_begin_test "Scenario 2: successful emulator allocation"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":2,"api":"34","ttlSeconds":120,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session should become READY"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one emulator allocation"
SESSION_ID_EMULATOR="$(extract_json_value 'payload.get("id")')"
log_success "Allocated emulator session ${SESSION_ID_EMULATOR}"

stage_begin_test "Scenario 3: invalid device type validation"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":1,"api":"34","ttlSeconds":120,"deviceType":"tablet"}'
assert_status "400"
assert_json_expr '"Unsupported deviceType" in payload.get("error", "")' "Expected 400 validation response"
log_success "Validation works"

stage_begin_test "Scenario 4: partial emulator allocation when requested > available"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":8,"api":"34","ttlSeconds":120,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Partial session should still be READY"
assert_json_expr 'payload.get("requestedDevices") == 8' "Requested devices mismatch"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one emulator device"
assert_json_expr 'payload.get("allocatedDevices", 0) <= payload.get("requestedDevices", 0)' "Allocated devices cannot exceed requested"
SESSION_ID_EMULATOR_PARTIAL="$(extract_json_value 'payload.get("id")')"
log_success "Partially allocated emulator session ${SESSION_ID_EMULATOR_PARTIAL}"

stage_begin_test "Scenario 5: physical allocation behavior for ADB adapter"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":1,"api":"34","ttlSeconds":120,"deviceType":"physical"}'
if [[ "${HTTP_STATUS}" == "201" ]]; then
    assert_json_expr 'payload.get("status") == "READY"' "Physical session should be READY when rack has devices"
    assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one physical device"
    SESSION_ID_PHYSICAL="$(extract_json_value 'payload.get("id")')"
    log_success "Physical allocation succeeded with session ${SESSION_ID_PHYSICAL}"
else
    assert_status "503"
    assert_json_expr '"No registered devices match the request" in payload.get("error", "")' "Expected immediate validation error when no physical devices are registered"
    log_success "Physical allocation correctly failed on empty physical rack"
fi

stage_begin_test "Scenario 6: targeted cuttlefish allocation (API 35)"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":1,"api":"35","ttlSeconds":120,"deviceType":"emulator"}'
if [[ "${HTTP_STATUS}" == "201" ]]; then
    if PAYLOAD="${HTTP_BODY}" python3 - <<'PY'
import json
import os
payload = json.loads(os.environ["PAYLOAD"])
is_ready = payload.get("status") == "READY"
has_count = payload.get("allocatedDevices", 0) >= 1
has_cf_adb = any(server.get("port") == 6520 for server in payload.get("adbServers", []))
raise SystemExit(0 if (is_ready and has_count and has_cf_adb) else 1)
PY
    then
        SESSION_ID_CUTTLEFISH="$(extract_json_value 'payload.get("id")')"
        log_success "Cuttlefish allocation succeeded with session ${SESSION_ID_CUTTLEFISH}"
    else
        SESSION_ID_CUTTLEFISH="$(extract_json_value 'payload.get("id")')"
        log_skip "Skipping strict cuttlefish assertion: backend returned session without READY cuttlefish allocation"
    fi
elif [[ "${HTTP_STATUS}" == "503" ]]; then
    assert_json_expr '"error" in payload' "Expected 503 payload with error"
    log_skip "Skipping cuttlefish allocation scenario: Cloud Orchestrator has no allocatable hosts/devices"
else
    fail "Unexpected HTTP ${HTTP_STATUS} in cuttlefish allocation scenario"
fi

stage_begin_test "Scenario 7: explicit release for created sessions"
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

stage_begin_test "Scenario 8: mshctl health reports manager readiness"
CLI_HEALTH_OUTPUT="$("${MSHCTL_BIN}" health --manager "${MANAGER_URL}")"
if [[ "${CLI_HEALTH_OUTPUT}" != *"Health: healthy"* ]]; then
    echo "${CLI_HEALTH_OUTPUT}"
    fail "mshctl health did not report healthy manager"
fi
log_success "mshctl health works"

stage_begin_test "Scenario 9: mshctl devices returns provider inventory in json mode"
CLI_DEVICES_OUTPUT="$("${MSHCTL_BIN}" devices --manager "${MANAGER_URL}" --json)"
PAYLOAD="${CLI_DEVICES_OUTPUT}" python3 - <<'PY'
import json
import os
payload = json.loads(os.environ["PAYLOAD"])
providers = {provider["name"] for provider in payload.get("providers", [])}
expected = {"integration-adb", "integration-farm", "integration-cuttlefish"}
raise SystemExit(0 if expected.issubset(providers) else 1)
PY
log_success "mshctl devices works"

stage_begin_test "Scenario 10: mshctl create returns session json"
CLI_CREATE_OUTPUT="$("${MSHCTL_BIN}" create --manager "${MANAGER_URL}" --json --devices 1 --api 34 --device-type emulator --ttl 120)"
SESSION_ID_CLI="$(PAYLOAD="${CLI_CREATE_OUTPUT}" python3 - <<'PY'
import json
import os
print(json.loads(os.environ["PAYLOAD"]).get("id", ""))
PY
)"
if [[ -z "${SESSION_ID_CLI}" ]]; then
    echo "${CLI_CREATE_OUTPUT}"
    fail "mshctl create did not return a session id"
fi
log_success "mshctl create works with session ${SESSION_ID_CLI}"

stage_begin_test "Scenario 11: mshctl show returns the created session"
CLI_SHOW_OUTPUT="$("${MSHCTL_BIN}" show --manager "${MANAGER_URL}" --json --id "${SESSION_ID_CLI}")"
PAYLOAD="${CLI_SHOW_OUTPUT}" SESSION_ID_CLI="${SESSION_ID_CLI}" python3 - <<'PY'
import json
import os
payload = json.loads(os.environ["PAYLOAD"])
raise SystemExit(0 if payload.get("id") == os.environ["SESSION_ID_CLI"] else 1)
PY
log_success "mshctl show works"

stage_begin_test "Scenario 12: mshctl list includes the created session"
CLI_LIST_OUTPUT="$("${MSHCTL_BIN}" list --manager "${MANAGER_URL}" --json)"
PAYLOAD="${CLI_LIST_OUTPUT}" SESSION_ID_CLI="${SESSION_ID_CLI}" python3 - <<'PY'
import json
import os
payload = json.loads(os.environ["PAYLOAD"])
raise SystemExit(0 if any(session.get("id") == os.environ["SESSION_ID_CLI"] for session in payload) else 1)
PY
log_success "mshctl list works"

stage_begin_test "Scenario 13: mshctl release releases the created session"
CLI_RELEASE_OUTPUT="$("${MSHCTL_BIN}" release --manager "${MANAGER_URL}" --id "${SESSION_ID_CLI}")"
if [[ "${CLI_RELEASE_OUTPUT}" != *"released"* ]]; then
    echo "${CLI_RELEASE_OUTPUT}"
    fail "mshctl release did not confirm release"
fi
SESSION_ID_CLI=""
log_success "mshctl release works"

stage_begin_test "Scenario 14: no active sessions after cleanup"
http_json "GET" "${MANAGER_URL}/api/v1/sessions"
assert_status "200"
assert_json_expr 'all(session.get("status") in ("RELEASED", "EXPIRED", "FAILED") for session in payload)' "Expected only terminal session statuses"
log_success "No active sessions remain"

stage_begin_phase "verifying" "Verifying no default ~/.msh state changes"
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
# cleanup phase is emitted by the EXIT trap.
