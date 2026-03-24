#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${REPO_ROOT}/tests/helpers/common.sh"
set_log_context "Docker Integration"

COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.integration.yml"
PROJECT_NAME="msh-integration"
MANAGER_URL="http://localhost:16037"
HARNESS_WORKSPACE="$(mktemp -d)"
DOCKER_COMPOSE_LOG=""
HARNESS_LOG_FILE=""
DOCKER_COMPOSE_VERBOSE="${MSH_VERBOSE_DOCKER_COMPOSE:-auto}"
SESSION_ID_EMULATOR_PRIMARY=""
SESSION_ID_EMULATOR_SECONDARY=""
SESSION_ID_PHYSICAL=""
SESSION_ID_CUTTLEFISH=""
CUTTLEFISH_AVAILABLE="0"

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
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" down -v --remove-orphans >/dev/null 2>&1 || true
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

log_step "Checking prerequisites"
require_command docker
require_command python3
require_command curl
if [[ "${MSH_SKIP_JENKINS_HARNESS:-false}" != "true" ]]; then
    require_command groovy
fi

log_step "Starting integration stack in docker-compose"
if [[ "${DOCKER_COMPOSE_VERBOSE}" == "true" ]]; then
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d --build
else
    if ! docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d --build >"${DOCKER_COMPOSE_LOG}" 2>&1; then
        cat "${DOCKER_COMPOSE_LOG}" || true
        fail "Failed to start docker-compose integration stack"
    fi
    log_info "docker-compose output captured in ${DOCKER_COMPOSE_LOG}"
    log_info "Set MSH_VERBOSE_DOCKER_COMPOSE=true for live compose logs"
fi
enable_dynamic_logs

log_step "Waiting for manager readiness at ${MANAGER_URL}"
if ! wait_for_http_json "${MANAGER_URL}/health" 120; then
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" logs --tail=200
    fail "Manager did not become healthy in time"
fi
log_success "Manager is healthy"

log_step "Scenario 1: manager health endpoint"
http_json "GET" "${MANAGER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("status") == "healthy"' "Manager /health should be healthy"
log_success "Health OK"

log_step "Scenario 2: all real adapters are registered and healthy"
http_json "GET" "${MANAGER_URL}/api/v1/devices"
assert_status "200"
assert_json_expr 'all(any(provider.get("name") == provider_name for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-farm", "integration-cuttlefish"])' "All integration providers must be present"
assert_json_expr 'all(any(provider.get("name") == provider_name and provider.get("status") == "HEALTHY" for provider in payload.get("providers", [])) for provider_name in ["integration-adb", "integration-farm", "integration-cuttlefish"])' "All integration providers must be healthy"
assert_json_expr 'any(provider.get("name") == "integration-adb" and "physical" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "ADB adapter should publish physical support"
assert_json_expr 'any(provider.get("name") == "integration-farm" and "emulator" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "Farm adapter should publish emulator support"
assert_json_expr 'any(provider.get("name") == "integration-cuttlefish" and "emulator" in provider.get("capabilities", {}).get("supportedDeviceTypes", []) for provider in payload.get("providers", []))' "Cuttlefish adapter should publish emulator support"
CUTTLEFISH_AVAILABLE="$(extract_json_value 'next((int(profile.get("count", 0)) for provider in payload.get("providers", []) if provider.get("name") == "integration-cuttlefish" for profile in provider.get("inventory", []) if profile.get("deviceType") == "emulator"), 0)')"
log_success "All real adapters are healthy and visible in manager inventory"

log_step "Scenario 3: successful emulator allocation (real farm/cuttlefish adapters)"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":2,"apiLevel":"34","ttlSeconds":180,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session should become READY"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one emulator allocation"
assert_json_expr 'len(payload.get("adbServers", [])) >= 1' "adbServers should be present"
SESSION_ID_EMULATOR_PRIMARY="$(extract_json_value 'payload.get("id")')"
log_success "Allocated emulator session ${SESSION_ID_EMULATOR_PRIMARY}"

log_step "Scenario 4: invalid deviceType validation"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":1,"apiLevel":"34","ttlSeconds":120,"deviceType":"tablet"}'
assert_status "400"
assert_json_expr '"Unsupported deviceType" in payload.get("error", "")' "Invalid deviceType should be rejected"
log_success "Validation response is correct"

log_step "Scenario 5: partial emulator allocation when requested > available"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":8,"apiLevel":"34","ttlSeconds":180,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Partial session should still be READY"
assert_json_expr 'payload.get("requestedDevices") == 8' "Requested devices mismatch"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one emulator device"
assert_json_expr 'payload.get("allocatedDevices", 0) <= payload.get("requestedDevices", 0)' "Allocated devices cannot exceed requested"
SESSION_ID_EMULATOR_SECONDARY="$(extract_json_value 'payload.get("id")')"
log_success "Partially allocated emulator session ${SESSION_ID_EMULATOR_SECONDARY}"

log_step "Scenario 6: physical allocation behavior for real ADB adapter"
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
    log_step "Scenario 7: targeted cuttlefish allocation (API 35)"
    http_json "POST" "${MANAGER_URL}/api/v1/sessions" '{"devices":1,"apiLevel":"35","ttlSeconds":180,"deviceType":"emulator"}'
    assert_status "201"
    assert_json_expr 'payload.get("status") == "READY"' "Cuttlefish session should become READY"
    assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Expected at least one cuttlefish device"
    assert_json_expr 'any(server.get("port") == 6520 for server in payload.get("adbServers", []))' "Expected cuttlefish ADB endpoint in adbServers"
    SESSION_ID_CUTTLEFISH="$(extract_json_value 'payload.get("id")')"
    log_success "Cuttlefish allocation succeeded with session ${SESSION_ID_CUTTLEFISH}"
else
    log_step "Scenario 7: targeted cuttlefish allocation (skipped, no available instances)"
    log_skip "Cuttlefish inventory count is 0 in this environment"
fi

log_step "Scenario 8: explicit release for emulator/physical sessions"
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
    log_step "Scenario 9: Jenkins shared-library harness run (skipped)"
    log_skip "Skipped because MSH_SKIP_JENKINS_HARNESS=true"
else
    log_step "Scenario 9: Jenkins shared-library harness run"
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

log_step "Scenario 10: no active sessions after cleanup"
http_json "GET" "${MANAGER_URL}/api/v1/sessions"
assert_status "200"
assert_json_expr 'all(session.get("status") in ("RELEASED", "EXPIRED", "FAILED") for session in payload)' "Expected only terminal session statuses"
log_success "No active sessions remain"

log_step "All docker-compose integration scenarios passed"
log_success "All docker-compose integration scenarios passed"
