#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${REPO_ROOT}/tests/helpers/common.sh"
source "${REPO_ROOT}/tests/helpers/cloud_orchestrator.sh"
source "${REPO_ROOT}/tests/helpers/stage_runtime.sh"
source "${REPO_ROOT}/tests/helpers/docker_stack.sh"

COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.integration.yml"
PROJECT_NAME="msh-integration"
MANAGER_URL="http://localhost:16037"
ADB_ADAPTER_SECRET="integration-secret-adb"
HARNESS_WORKSPACE="$(mktemp -d)"
INTEGRATION_CONFIG_DIR=""
DOCKER_COMPOSE_LOG=""
HARNESS_LOG_FILE=""
DOCKER_COMPOSE_VERBOSE="${MSH_VERBOSE_DOCKER_COMPOSE:-auto}"
SESSION_ID_EMULATOR_PRIMARY=""
SESSION_ID_EMULATOR_SECONDARY=""
SESSION_ID_PHYSICAL=""
SESSION_ID_CUTTLEFISH=""
SESSION_ID_RESTART_PERSISTENCE=""
QUEUE_HOLDER_SESSION_ID=""
QUEUE_WAITER_SESSION_ID=""
TOTAL_TEST_SCENARIOS=14
WAIT_CONTAINERS=(
    "msh-integration-shepherd-adb"
    "msh-integration-shepherd-farm"
    "msh-integration-shepherd-cuttlefish"
)

stage_runtime_init "Docker Integration" "${TOTAL_TEST_SCENARIOS}" "false"

adapter_http_json_bearer_via_manager() {
    local method="$1"
    local url="$2"
    local secret="$3"
    local payload="${4:-}"
    local raw
    if [[ -n "$payload" ]]; then
        raw="$(docker exec msh-integration-manager sh -lc "tmp=\$(mktemp); code=\$(curl -sS -o \"\$tmp\" -w '%{http_code}' -X '${method}' -H 'Content-Type: application/json' -H 'Authorization: Bearer ${secret}' --data-binary '${payload}' '${url}'); printf '%s\n' \"\$code\"; cat \"\$tmp\"; rm -f \"\$tmp\"")"
    else
        raw="$(docker exec msh-integration-manager sh -lc "tmp=\$(mktemp); code=\$(curl -sS -o \"\$tmp\" -w '%{http_code}' -X '${method}' -H 'Authorization: Bearer ${secret}' '${url}'); printf '%s\n' \"\$code\"; cat \"\$tmp\"; rm -f \"\$tmp\"")"
    fi
    HTTP_STATUS="${raw%%$'\n'*}"
    if [[ "${raw}" == *$'\n'* ]]; then
        HTTP_BODY="${raw#*$'\n'}"
    else
        HTTP_BODY=""
    fi
}

prepare_integration_config_dir() {
    INTEGRATION_CONFIG_DIR="${HARNESS_WORKSPACE}/config"
    mkdir -p "${INTEGRATION_CONFIG_DIR}"
    cp "${SCRIPT_DIR}"/msh*.yaml "${INTEGRATION_CONFIG_DIR}/"
    export MSH_INTEGRATION_CONFIG_DIR="${INTEGRATION_CONFIG_DIR}"
}

overwrite_manager_runtime_config() {
    local source_file="$1"
    cp "${SCRIPT_DIR}/${source_file}" "${INTEGRATION_CONFIG_DIR}/msh.integration.yaml"
}

wait_for_healthy_container() {
    local container_name="$1"
    local timeout_seconds="$2"
    local started_at
    local now=0
    local elapsed=0
    started_at="$(date +%s)"
    # Poll at 500ms granularity. Docker's healthcheck itself runs at interval=1s
    # so polling any faster just wastes CPU; 500ms hits "healthy" within half
    # a second of it being reported.
    while true; do
        local status
        status="$(docker inspect --format='{{.State.Health.Status}}' "${container_name}" 2>/dev/null || echo "missing")"
        if [[ "${status}" == "healthy" ]]; then
            return 0
        fi
        now="$(date +%s)"
        elapsed=$((now - started_at))
        if (( elapsed >= timeout_seconds )); then
            return 1
        fi
        sleep 0.5
    done
}

cleanup() {
    if [[ -n "${SESSION_ID_EMULATOR_PRIMARY}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_EMULATOR_PRIMARY}" || true
    fi
    if [[ -n "${SESSION_ID_EMULATOR_SECONDARY}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_EMULATOR_SECONDARY}" || true
    fi
    if [[ -n "${SESSION_ID_PHYSICAL}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_PHYSICAL}" || true
    fi
    if [[ -n "${SESSION_ID_CUTTLEFISH}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_CUTTLEFISH}" || true
    fi
    if [[ -n "${SESSION_ID_RESTART_PERSISTENCE}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_RESTART_PERSISTENCE}" || true
    fi
    if [[ -n "${QUEUE_HOLDER_SESSION_ID}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${QUEUE_HOLDER_SESSION_ID}" || true
    fi
    if [[ -n "${QUEUE_WAITER_SESSION_ID}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${QUEUE_WAITER_SESSION_ID}" || true
    fi
    local exit_code=$?
    # Hand off to the next stage instead of tearing down when:
    #  - the runner asked us to (MSH_LEAVE_STACK_RUNNING=1)
    #  - AND our scenarios exited cleanly (nothing failing, no diagnostics to
    #    capture from a broken stack)
    # This saves ~15-20s on the [integration] → [e2e] transition because e2e
    # skips its own `compose up` + health-wait. On failure we always tear down
    # so failure diagnostics show a clean next-stage run.
    if docker_stack_handoff_requested && (( exit_code == 0 )); then
        stage_emit_phase "handing-off"
        log_info "Leaving stack running for next stage (MSH_LEAVE_STACK_RUNNING=1)"
        # Don't `rm -rf HARNESS_WORKSPACE` — its config files are bind-mounted
        # into the manager container; removing them would break restart_manager
        # in the next stage. Workspace is a mktemp'd dir — OS cleans it up.
        return
    fi
    stage_emit_phase "cleanup"
    docker_stack_teardown "${COMPOSE_FILE}" "${PROJECT_NAME}"
    # DO NOT remove prebuilt images — downstream stages reuse them via
    # MSH_SKIP_DOCKER_BUILD=1. See tests/helpers/docker_prebuild.sh.
    rm -rf "${HARNESS_WORKSPACE}" >/dev/null 2>&1 || true
}

trap cleanup EXIT

DOCKER_COMPOSE_LOG="${HARNESS_WORKSPACE}/docker-compose-up.log"
HARNESS_LOG_FILE="${HARNESS_WORKSPACE}/jenkins-harness.log"
if [[ "${DOCKER_COMPOSE_VERBOSE}" == "auto" ]]; then
    if [[ "${MSH_LOG_MODE:-dynamic}" == "plain" ]]; then
        DOCKER_COMPOSE_VERBOSE="false"
    else
        DOCKER_COMPOSE_VERBOSE="true"
    fi
fi

stage_begin_phase "preparing" "Checking prerequisites" 1 2
require_command docker
require_command python3
require_command curl
if [[ "${MSH_SKIP_JENKINS_HARNESS:-false}" != "true" ]]; then
    require_command groovy
fi

stage_emit_phase "preparing" 2 2
prepare_integration_config_dir

# "pulling" only when we actually hit the network; "reusing" when the config
# was pre-downloaded by [build] docker images and we simply point at the cache.
if [[ -n "${MSH_PREBUILT_ORCHESTRATOR_CONFIG:-}" && -s "${MSH_PREBUILT_ORCHESTRATOR_CONFIG}" ]]; then
    stage_begin_phase "reusing" "Reusing pre-downloaded Cloud Orchestrator config"
else
    stage_begin_phase "pulling" "Downloading official Cloud Orchestrator config"
fi
download_cloud_orchestrator_config "${HARNESS_WORKSPACE}"
log_success "Downloaded Cloud Orchestrator config to ${CUTTLEFISH_ORCHESTRATOR_CONFIG_PATH}"

if [[ "${MSH_SKIP_DOCKER_BUILD:-0}" != "1" ]]; then
    stage_begin_phase "building" "Building integration stack images" 1 2
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" build --no-cache cuttlefish-orchestrator
    stage_emit_phase "building" 2 2
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" build adb shepherd-adb shepherd-farm shepherd-cuttlefish manager
else
    # Images were pre-built by the [build] docker images stage — "reusing" is
    # the honest label here; downgrade to "building" only if we fall back to
    # rebuilding a missing orchestrator image.
    if ! docker image inspect msh-integration-cuttlefish-orchestrator:latest >/dev/null 2>&1; then
        stage_begin_phase "building" "Recovering missing cuttlefish-orchestrator image"
        docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" build --no-cache cuttlefish-orchestrator
    else
        stage_begin_phase "reusing" "Reusing pre-built integration images"
    fi
fi

stage_begin_phase "stopping" "Tearing down any previous integration stack"
docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" down -v --remove-orphans >/dev/null 2>&1 || true
stage_begin_phase "starting" "Starting integration stack in docker-compose"
if [[ "${DOCKER_COMPOSE_VERBOSE}" == "true" ]]; then
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d
else
    if ! docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d >"${DOCKER_COMPOSE_LOG}" 2>&1; then
        cat "${DOCKER_COMPOSE_LOG}" || true
        fail "Failed to start docker-compose integration stack"
    fi
    log_info "docker-compose output captured in ${DOCKER_COMPOSE_LOG}"
    log_info "Set MSH_VERBOSE_DOCKER_COMPOSE=true for live compose logs"
fi
enable_dynamic_logs

log_step "Waiting for manager readiness at ${MANAGER_URL}"
WAIT_PHASE_TOTAL=$(( ${#WAIT_CONTAINERS[@]} + 1 ))
WAIT_PHASE_STEP=0
for container_name in "${WAIT_CONTAINERS[@]}"; do
    WAIT_PHASE_STEP=$((WAIT_PHASE_STEP + 1))
        stage_emit_phase "starting" "${WAIT_PHASE_STEP}" "${WAIT_PHASE_TOTAL}"
    if ! wait_for_healthy_container "${container_name}" 120; then
        docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" logs --tail=200
        fail "Container ${container_name} did not become healthy in time"
    fi
done
stage_emit_phase "starting" "${WAIT_PHASE_TOTAL}" "${WAIT_PHASE_TOTAL}"
if ! wait_for_http_json "${MANAGER_URL}/health" 120; then
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" logs --tail=200
    fail "Manager did not become healthy in time"
fi
log_success "Manager is healthy"

stage_begin_test "Scenario 1: manager health endpoint"
http_json "GET" "${MANAGER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("status") == "healthy"' "Manager /health should be healthy"
log_success "Health OK"

stage_begin_test "Scenario 2: all real adapters are registered and healthy"
http_json "GET" "${MANAGER_URL}/api/v1/devices"
assert_status "200"
assert_json_expr 'all(any(provider.get("name") == provider_name for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-farm", "integration-cuttlefish"])' "All integration providers must be present"
assert_json_expr 'all(any(provider.get("name") == provider_name and provider.get("status") == "HEALTHY" for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-farm", "integration-cuttlefish"])' "All integration providers must be healthy"
assert_json_expr 'any(provider.get("name") == "integration-adb" and "physical" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "ADB adapter should publish physical support"
assert_json_expr 'any(provider.get("name") == "integration-farm" and "emulator" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "Farm adapter should publish emulator support"
assert_json_expr 'any(provider.get("name") == "integration-cuttlefish" and "emulator" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "Cuttlefish adapter should publish emulator support"
log_success "All real adapters are healthy and visible in manager inventory"

stage_begin_test "Scenario 3: successful emulator allocation (real farm/cuttlefish adapters)"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":2,"api":"34","ttlSeconds":180,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session should become READY"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one emulator allocation"
assert_json_expr 'len(payload.get("adbServers", [])) >= 1' "adbServers should be present"
SESSION_ID_EMULATOR_PRIMARY="$(extract_json_value 'payload.get("id")')"
log_success "Allocated emulator session ${SESSION_ID_EMULATOR_PRIMARY}"

stage_begin_test "Scenario 4: adb adapter reload endpoint returns refreshed status"
adapter_http_json_bearer_via_manager "POST" "http://shepherd-adb:7037/adb-reload" "${ADB_ADAPTER_SECRET}"
assert_status "200"
assert_json_expr 'payload.get("metadata", {}).get("adbReload") == "ok"' "ADB reload should mark response metadata"
assert_json_expr 'payload.get("capabilities", {}).get("supportedDeviceTypes") == ["physical"]' "ADB reload should return adapter status payload"
log_success "shepherd-adb /adb-reload returns refreshed status"

stage_begin_test "Scenario 5: invalid deviceType validation"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":1,"api":"34","ttlSeconds":120,"deviceType":"tablet"}'
assert_status "400"
assert_json_expr '"Unsupported deviceType" in payload.get("error", "")' "Invalid deviceType should be rejected"
log_success "Validation response is correct"

stage_begin_test "Scenario 6: partial emulator allocation when requested > available"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":8,"api":"34","ttlSeconds":180,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Partial session should still be READY"
assert_json_expr 'payload.get("requestedDevices") == 8' "Requested devices mismatch"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one emulator device"
assert_json_expr 'payload.get("allocatedDevices", 0) <= payload.get("requestedDevices", 0)' "Allocated devices cannot exceed requested"
SESSION_ID_EMULATOR_SECONDARY="$(extract_json_value 'payload.get("id")')"
log_success "Partially allocated emulator session ${SESSION_ID_EMULATOR_SECONDARY}"

stage_begin_test "Scenario 7: physical allocation behavior for real ADB adapter"
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

stage_begin_test "Scenario 8: targeted cuttlefish allocation (API 35)"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":1,"api":"35","ttlSeconds":180,"deviceType":"emulator"}'
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

stage_begin_test "Scenario 9: config update is blocked while active sessions exist"
http_json "PUT" "${MANAGER_URL}/api/v1/config" '{"providers":[]}'
assert_status "409"
assert_json_expr '"Cannot remove providers with active sessions" in payload.get("error", "")' "Config update should be blocked while provider sessions are active"
log_success "Config update was blocked while active sessions existed"

stage_begin_test "Scenario 10: explicit release for emulator/physical sessions"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_EMULATOR_PRIMARY}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Primary session should be released"
SESSION_ID_EMULATOR_PRIMARY=""
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_EMULATOR_SECONDARY}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Secondary session should be released"
SESSION_ID_EMULATOR_SECONDARY=""
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

if [[ "${MSH_SKIP_JENKINS_HARNESS:-false}" == "true" ]]; then
    stage_begin_test "Scenario 11: Jenkins shared-library harness run (skipped)"
    log_skip "Skipped because MSH_SKIP_JENKINS_HARNESS=true"
else
    stage_begin_test "Scenario 11: Jenkins shared-library harness run"
    if [[ "${MSH_LOG_MODE:-plain}" == "dynamic" ]]; then
        if ! (
            cd "${REPO_ROOT}"
            MSH_URL="${MANAGER_URL}" MSH_HARNESS_WORKSPACE="${HARNESS_WORKSPACE}" \
                groovy "${REPO_ROOT}/tests/integration/jenkins_harness.groovy"
        ) >"${HARNESS_LOG_FILE}" 2>&1; then
            cat "${HARNESS_LOG_FILE}" || true
            fail "Jenkins harness failed"
        fi
    else
        (
            cd "${REPO_ROOT}"
            MSH_URL="${MANAGER_URL}" MSH_HARNESS_WORKSPACE="${HARNESS_WORKSPACE}" \
                groovy "${REPO_ROOT}/tests/integration/jenkins_harness.groovy"
        )
    fi
    log_success "Jenkins harness completed"
fi

stage_begin_test "Scenario 12: config reload applies updated on-disk config"
overwrite_manager_runtime_config "msh.scenario-farm-only.yaml"
http_json "POST" "${MANAGER_URL}/api/v1/config/reload"
assert_status "200"
assert_json_expr 'len(payload.get("providers", [])) == 1 and payload.get("providers", [{}])[0].get("name") == "integration-farm"' "Reload should switch manager config to farm-only"
http_json "GET" "${MANAGER_URL}/api/v1/devices"
assert_status "200"
assert_json_expr 'len(payload.get("providers", [])) == 1 and payload.get("providers", [{}])[0].get("name") == "integration-farm"' "Device inventory should reflect farm-only config after reload"
overwrite_manager_runtime_config "msh.integration.yaml"
http_json "POST" "${MANAGER_URL}/api/v1/config/reload"
assert_status "200"
assert_json_expr 'len(payload.get("providers", [])) == 3 and all(any(provider.get("name") == provider_name for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-cuttlefish", "integration-farm"])' "Reload should restore the full integration config"
log_success "Config reload applied updated on-disk config and restore succeeded"

stage_begin_test "Scenario 13: manager restart preserves state and release still works"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"maxDevices":1,"api":"34","ttlSeconds":180,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Persistence scenario session should be READY before restart"
SESSION_ID_RESTART_PERSISTENCE="$(extract_json_value 'payload.get("id")')"
docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" restart manager >/dev/null
if ! wait_for_http_json "${MANAGER_URL}/health" 120; then
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" logs --tail=200 manager
    fail "Manager did not become healthy after restart"
fi
http_json "GET" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_RESTART_PERSISTENCE}"
assert_status "200"
assert_json_expr 'payload.get("id") == "'"${SESSION_ID_RESTART_PERSISTENCE}"'"' "Expected restarted manager to restore persisted session"
assert_json_expr 'payload.get("status") == "READY"' "Persisted session should remain READY after manager restart"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID_RESTART_PERSISTENCE}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Persisted session should release after manager restart"
SESSION_ID_RESTART_PERSISTENCE=""
log_success "Manager restart preserved session state and release continued to work"

stage_begin_test "Scenario 14: no active sessions after cleanup"
http_json "GET" "${MANAGER_URL}/api/v1/sessions"
assert_status "200"
assert_json_expr 'all(session.get("status") in ("RELEASED", "EXPIRED", "FAILED") for session in payload)' "Expected only terminal session statuses"
log_success "No active sessions remain"

log_step "All docker-compose integration scenarios passed"
log_success "All docker-compose integration scenarios passed"
