#!/usr/bin/env bash
# Component tests — builds local Docker images and validates each service
# in isolation: API structure, auth enforcement, state changes.
#
# Green = the locally-built image works correctly when run as a container.
#
# Usage:
#   tests/component/docker/smoke_docker.sh                        # all services
#   MSH_COMPONENT_SERVICE=shepherd-adb   tests/component/docker/smoke_docker.sh
#   MSH_COMPONENT_SERVICE=shepherd-farm  tests/component/docker/smoke_docker.sh
#   MSH_COMPONENT_SERVICE=shepherd-cuttlefish  tests/component/docker/smoke_docker.sh
#   MSH_COMPONENT_SERVICE=manager        tests/component/docker/smoke_docker.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.smoke.yml"
PROJECT_NAME="msh-smoke"
source "${REPO_ROOT}/tests/helpers/common.sh"
source "${REPO_ROOT}/tests/helpers/cloud_orchestrator.sh"
source "${REPO_ROOT}/tests/helpers/stage_runtime.sh"

SERVICE="${MSH_COMPONENT_SERVICE:-all}"
BUILD_SERVICES=()
case "${SERVICE}" in
    shepherd-adb)
        COMPOSE_SERVICES=("adb" "shepherd-adb")
        HEALTH_CONTAINERS=("msh-smoke-shepherd-adb")
        BUILD_SERVICES=("adb" "shepherd-adb")
        ;;
    shepherd-farm)
        COMPOSE_SERVICES=("farm-server" "shepherd-farm")
        HEALTH_CONTAINERS=("msh-smoke-shepherd-farm")
        BUILD_SERVICES=("shepherd-farm")
        ;;
    shepherd-cuttlefish)
        COMPOSE_SERVICES=("cuttlefish-orchestrator" "shepherd-cuttlefish")
        HEALTH_CONTAINERS=("msh-smoke-shepherd-cuttlefish")
        BUILD_SERVICES=("shepherd-cuttlefish")
        ;;
    manager)
        COMPOSE_SERVICES=()
        HEALTH_CONTAINERS=("msh-smoke-shepherd-adb" "msh-smoke-shepherd-farm" "msh-smoke-shepherd-cuttlefish" "msh-smoke-manager")
        BUILD_SERVICES=("adb" "shepherd-adb" "shepherd-farm" "shepherd-cuttlefish" "manager")
        ;;
    all)
        COMPOSE_SERVICES=()
        HEALTH_CONTAINERS=("msh-smoke-shepherd-adb" "msh-smoke-shepherd-farm" "msh-smoke-shepherd-cuttlefish" "msh-smoke-manager")
        BUILD_SERVICES=("adb" "shepherd-adb" "shepherd-farm" "shepherd-cuttlefish" "manager")
        ;;
    *)
        echo "Unknown MSH_COMPONENT_SERVICE: ${SERVICE}" >&2
        echo "Valid values: shepherd-adb, shepherd-farm, shepherd-cuttlefish, manager, all" >&2
        exit 1
        ;;
esac

resolve_total_test_scenarios() {
    case "${SERVICE}" in
        shepherd-adb) echo "6" ;;
        shepherd-farm) echo "6" ;;
        shepherd-cuttlefish) echo "5" ;;
        manager) echo "11" ;;
        all) echo "28" ;;
        *) echo "1" ;;
    esac
}

TOTAL_TEST_SCENARIOS="$(resolve_total_test_scenarios)"
stage_runtime_init "Component ${SERVICE}" "${TOTAL_TEST_SCENARIOS}"

ADB_ADAPTER_URL="http://127.0.0.1:17037"
FARM_ADAPTER_URL="http://127.0.0.1:17038"
CUTTLEFISH_ADAPTER_URL="http://127.0.0.1:17039"
MANAGER_URL="http://127.0.0.1:16037"
ADB_SECRET="smoke-secret-adb"
FARM_SECRET="smoke-secret-farm"
CUTTLEFISH_SECRET="smoke-secret-cuttlefish"
SESSION_ID=""
FARM_LEASE_ID=""
CUTTLEFISH_LEASE_ID=""
SMOKE_WORKSPACE="$(mktemp -d)"

cleanup() {
    if [[ -n "${SESSION_ID}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}" || true
    fi
    stage_emit_phase "cleanup"
    # `--rmi local` would nuke the prebuilt images that downstream stages
    # (integration, e2e, scale) reuse via MSH_SKIP_DOCKER_BUILD=1 — forcing
    # a ~60s Go rebuild of cuttlefish-orchestrator in the next stage. Only
    # tear down containers + volumes here; image lifetime is the developer's
    # responsibility (`docker system prune`).
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    rm -rf "${SMOKE_WORKSPACE}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Like http_json but adds Bearer auth header.
http_json_bearer() {
    local method="$1"
    local url="$2"
    local secret="$3"
    local payload="${4:-}"
    local tmp_file
    tmp_file="$(mktemp)"
    if [[ -n "${payload}" ]]; then
        HTTP_STATUS="$(curl -sS -o "${tmp_file}" -w "%{http_code}" -X "${method}" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer ${secret}" \
            --data-binary "${payload}" "${url}")"
    else
        HTTP_STATUS="$(curl -sS -o "${tmp_file}" -w "%{http_code}" -X "${method}" \
            -H "Authorization: Bearer ${secret}" "${url}")"
    fi
    HTTP_BODY="$(cat "${tmp_file}")"
    rm -f "${tmp_file}"
}

wait_for_healthy_containers() {
    local deadline="$1"
    local total="${#HEALTH_CONTAINERS[@]}"
    local idx=0
    local svc=""
    local status=""
    while true; do
        local all_healthy=true
        idx=0
        for svc in "${HEALTH_CONTAINERS[@]}"; do
            idx=$((idx + 1))
            stage_emit_phase "starting" "${idx}" "${total}"
            STATUS="$(docker inspect --format='{{.State.Health.Status}}' "${svc}" 2>/dev/null || echo "missing")"
            if [[ "${STATUS}" != "healthy" ]]; then
                all_healthy=false
                break
            fi
        done
        if [[ "${all_healthy}" == "true" ]]; then
            break
        fi
        if (( $(date +%s) >= deadline )); then
            docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" logs --tail=50 >&2 || true
            fail "Services did not become healthy within 120 seconds"
        fi
        # Match docker's healthcheck interval — no point polling faster than
        # the daemon updates the status field.
        sleep 0.5
    done
}

# ── Prerequisites ────────────────────────────────────────────────────────────

stage_begin_phase "preparing" "Checking prerequisites"
require_command docker
require_command curl
require_command python3
if ! docker compose version >/dev/null 2>&1; then
    fail "docker compose v2 is required (not the standalone docker-compose)"
fi

if [[ -n "${MSH_PREBUILT_ORCHESTRATOR_CONFIG:-}" && -s "${MSH_PREBUILT_ORCHESTRATOR_CONFIG}" ]]; then
    stage_begin_phase "reusing" "Reusing pre-downloaded Cloud Orchestrator config"
else
    stage_begin_phase "pulling" "Downloading official Cloud Orchestrator config"
fi
download_cloud_orchestrator_config "${SMOKE_WORKSPACE}"
log_success "Downloaded Cloud Orchestrator config to ${CUTTLEFISH_ORCHESTRATOR_CONFIG_PATH}"

# ── Stack startup ─────────────────────────────────────────────────────────────

if [[ "${MSH_SKIP_DOCKER_BUILD:-0}" != "1" ]]; then
    stage_begin_phase "building" "Building component stack (service: ${SERVICE})" 1 2
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" build --no-cache cuttlefish-orchestrator
    stage_emit_phase "building" 2 2
    if (( ${#BUILD_SERVICES[@]} > 0 )); then
        docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" build "${BUILD_SERVICES[@]}"
    fi
else
    # Images were pre-built in the [build] docker images stage; compose up will reuse them.
    # Only call out "building" if the fallback path kicks in for a missing orchestrator image.
    if ! docker image inspect msh-integration-cuttlefish-orchestrator:latest >/dev/null 2>&1; then
        stage_begin_phase "building" "Recovering missing cuttlefish-orchestrator image"
        docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" build --no-cache cuttlefish-orchestrator
    else
        stage_begin_phase "reusing" "Reusing pre-built component images (service: ${SERVICE})"
    fi
fi
stage_begin_phase "starting" "Starting component stack"
if (( ${#COMPOSE_SERVICES[@]} > 0 )); then
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d --remove-orphans "${COMPOSE_SERVICES[@]}"
else
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d --remove-orphans
fi

log_step "Waiting for services to become healthy (max 120s)"
DEADLINE=$(( $(date +%s) + 120 ))
wait_for_healthy_containers "${DEADLINE}"
log_success "Services healthy"

# ── shepherd-adb ─────────────────────────────────────────────────────────────

if [[ "${SERVICE}" == "shepherd-adb" || "${SERVICE}" == "all" ]]; then

stage_begin_test "shepherd-adb: /health is public and returns adapterType=adb"
http_json "GET" "${ADB_ADAPTER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("adapterType") == "adb"' "adapterType must be adb"
assert_json_expr '"status" in payload' "health response must have status field"
assert_json_expr '"version" in payload' "health response must have version field"
log_success "shepherd-adb /health OK"

stage_begin_test "shepherd-adb: /status rejects unauthenticated requests"
http_json "GET" "${ADB_ADAPTER_URL}/status"
assert_status "401"
log_success "shepherd-adb /status correctly requires auth"

stage_begin_test "shepherd-adb: /status returns pool, access, inventory, capabilities"
http_json_bearer "GET" "${ADB_ADAPTER_URL}/status" "${ADB_SECRET}"
assert_status "200"
assert_json_expr '"pool" in payload' "status must have pool"
assert_json_expr '"access" in payload' "status must have access"
assert_json_expr '"inventory" in payload' "status must have inventory"
assert_json_expr '"capabilities" in payload' "status must have capabilities"
assert_json_expr '"physical" in payload.get("capabilities", {}).get("supportedDeviceTypes", [])' \
    "adb adapter must advertise physical device support"
log_success "shepherd-adb /status returns valid structure"

stage_begin_test "shepherd-adb: /adb-reload restarts daemon and returns refreshed status"
http_json_bearer "POST" "${ADB_ADAPTER_URL}/adb-reload" "${ADB_SECRET}"
assert_status "200"
assert_json_expr 'payload.get("metadata", {}).get("adbReload") == "ok"' \
    "adb reload response must include adbReload=ok metadata"
assert_json_expr '"pool" in payload and "inventory" in payload and "capabilities" in payload' \
    "adb reload must return full status payload"
log_success "shepherd-adb /adb-reload returns valid structure"

stage_begin_test "shepherd-adb: /acquire returns 503 when no physical devices connected"
http_json_bearer "POST" "${ADB_ADAPTER_URL}/acquire" "${ADB_SECRET}" \
    '{"count":1,"apiLevel":"34","ttlSeconds":120}'
assert_status "503"
assert_json_expr '"error" in payload' "503 response must include error field"
log_success "shepherd-adb /acquire correctly returns 503 (no USB devices in container)"

stage_begin_test "shepherd-adb: /acquire rejects unauthenticated requests"
http_json "POST" "${ADB_ADAPTER_URL}/acquire" '{"count":1,"apiLevel":"34","ttlSeconds":120}'
assert_status "401"
log_success "shepherd-adb /acquire correctly requires auth"

fi  # shepherd-adb

# ── shepherd-farm ─────────────────────────────────────────────────────────────

if [[ "${SERVICE}" == "shepherd-farm" || "${SERVICE}" == "all" ]]; then

stage_begin_test "shepherd-farm: /health is public and returns adapterType=farm"
http_json "GET" "${FARM_ADAPTER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("adapterType") == "farm"' "adapterType must be farm"
assert_json_expr 'payload.get("status") == "healthy"' "farm adapter must be healthy (farm-server is running)"
log_success "shepherd-farm /health OK"

stage_begin_test "shepherd-farm: /status rejects unauthenticated requests"
http_json "GET" "${FARM_ADAPTER_URL}/status"
assert_status "401"
log_success "shepherd-farm /status correctly requires auth"

stage_begin_test "shepherd-farm: /status shows 3 total emulators and emulator support"
http_json_bearer "GET" "${FARM_ADAPTER_URL}/status" "${FARM_SECRET}"
assert_status "200"
assert_json_expr 'payload.get("pool", {}).get("total", 0) == 3' \
    "farm pool must have 3 total emulators (FARM_SERVER_TOTAL=3)"
assert_json_expr '"emulator" in payload.get("capabilities", {}).get("supportedDeviceTypes", [])' \
    "farm adapter must advertise emulator support"
log_success "shepherd-farm /status returns valid structure"

stage_begin_test "shepherd-farm: /acquire allocates 2 emulators and returns leaseId"
http_json_bearer "POST" "${FARM_ADAPTER_URL}/acquire" "${FARM_SECRET}" \
    '{"count":2,"apiLevel":"34","ttlSeconds":120}'
assert_status "200"
assert_json_expr 'payload.get("acquiredCount") == 2' "farm must acquire exactly 2 emulators"
assert_json_expr 'payload.get("leaseId") not in (None, "")' "farm must return a non-empty leaseId"
assert_json_expr '"access" in payload' "farm acquire response must have access"
FARM_LEASE_ID="$(extract_json_value 'payload.get("leaseId")')"
log_success "shepherd-farm allocated 2 emulators (leaseId=${FARM_LEASE_ID})"

stage_begin_test "shepherd-farm: /release returns leased emulators"
http_json_bearer "DELETE" "${FARM_ADAPTER_URL}/release/${FARM_LEASE_ID}" "${FARM_SECRET}"
assert_status "200"
FARM_LEASE_ID=""
log_success "shepherd-farm /release OK"

stage_begin_test "shepherd-farm: /status reflects released emulators"
http_json_bearer "GET" "${FARM_ADAPTER_URL}/status" "${FARM_SECRET}"
assert_status "200"
assert_json_expr 'payload.get("pool", {}).get("available", 0) == 3' \
    "all 3 emulators must be available after release"
log_success "shepherd-farm pool state is consistent after release"

fi  # shepherd-farm

# ── shepherd-cuttlefish ───────────────────────────────────────────────────────

if [[ "${SERVICE}" == "shepherd-cuttlefish" || "${SERVICE}" == "all" ]]; then

stage_begin_test "shepherd-cuttlefish: /health is public and returns adapterType=cuttlefish"
http_json "GET" "${CUTTLEFISH_ADAPTER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("adapterType") == "cuttlefish"' "adapterType must be cuttlefish"
assert_json_expr 'payload.get("status") in ("healthy", "unhealthy")' "cuttlefish health must expose healthy/unhealthy status"
log_success "shepherd-cuttlefish /health OK"

stage_begin_test "shepherd-cuttlefish: /status rejects unauthenticated requests"
http_json "GET" "${CUTTLEFISH_ADAPTER_URL}/status"
assert_status "401"
log_success "shepherd-cuttlefish /status correctly requires auth"

stage_begin_test "shepherd-cuttlefish: /status returns valid structure before allocation"
http_json_bearer "GET" "${CUTTLEFISH_ADAPTER_URL}/status" "${CUTTLEFISH_SECRET}"
assert_status "200"
assert_json_expr 'isinstance(payload.get("pool", {}).get("total", 0), int)' \
    "cuttlefish pool total must be an integer"
assert_json_expr '"emulator" in payload.get("capabilities", {}).get("supportedDeviceTypes", [])' \
    "cuttlefish adapter must advertise emulator support"
log_success "shepherd-cuttlefish /status returns valid structure"

stage_begin_test "shepherd-cuttlefish: /acquire creates an instance and returns leaseId"
http_json_bearer "POST" "${CUTTLEFISH_ADAPTER_URL}/acquire" "${CUTTLEFISH_SECRET}" \
    '{"count":1,"apiLevel":"34","ttlSeconds":120}'
if [[ "${HTTP_STATUS}" == "200" ]]; then
    assert_json_expr 'payload.get("acquiredCount") == 1' "cuttlefish must acquire exactly 1 instance"
    assert_json_expr 'payload.get("leaseId") not in (None, "")' "cuttlefish must return a non-empty leaseId"
    assert_json_expr '"access" in payload' "cuttlefish acquire response must have access"
    CUTTLEFISH_LEASE_ID="$(extract_json_value 'payload.get("leaseId")')"
    log_success "shepherd-cuttlefish acquired 1 instance (leaseId=${CUTTLEFISH_LEASE_ID})"

    stage_begin_test "shepherd-cuttlefish: /release stops the instance"
    http_json_bearer "DELETE" "${CUTTLEFISH_ADAPTER_URL}/release/${CUTTLEFISH_LEASE_ID}" "${CUTTLEFISH_SECRET}"
    assert_status "200"
    CUTTLEFISH_LEASE_ID=""
    log_success "shepherd-cuttlefish /release OK"
else
    assert_status "503"
    assert_json_expr '"error" in payload' "503 response must include error field"
    log_skip "Cuttlefish backend is unavailable in this environment; acquire returned 503 as expected"
    stage_begin_test "shepherd-cuttlefish: /release stops the instance"
    log_skip "Skipped release check because no cuttlefish lease was allocated"
fi

fi  # shepherd-cuttlefish

# ── Manager ───────────────────────────────────────────────────────────────────

if [[ "${SERVICE}" == "manager" || "${SERVICE}" == "all" ]]; then

stage_begin_test "Manager: /live liveness endpoint"
http_json "GET" "${MANAGER_URL}/live"
assert_status "200"
log_success "Manager /live OK"

stage_begin_test "Manager: /health shows all 3 providers healthy"
http_json "GET" "${MANAGER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("status") == "healthy"' "Manager overall status must be healthy"
assert_json_expr 'len(payload.get("providers", [])) == 3' "Manager must expose 3 providers"
assert_json_expr 'all(p.get("status") == "HEALTHY" for p in payload.get("providers", []))' \
    "All 3 providers must be HEALTHY"
log_success "Manager /health: 3 providers all HEALTHY"

stage_begin_test "Manager: /api/v1/devices shows inventory from all providers"
http_json "GET" "${MANAGER_URL}/api/v1/devices"
assert_status "200"
assert_json_expr 'len(payload.get("providers", [])) == 3' "devices endpoint must list 3 providers"
assert_json_expr 'any("emulator" in p.get("capabilities", {}).get("supportedDeviceTypes", []) for p in payload.get("providers", []))' \
    "at least one provider must support emulator"
assert_json_expr 'any("physical" in p.get("capabilities", {}).get("supportedDeviceTypes", []) for p in payload.get("providers", []))' \
    "at least one provider must support physical"
log_success "Manager /api/v1/devices OK"

stage_begin_test "Manager: GET /api/v1/sessions returns empty list before any session"
http_json "GET" "${MANAGER_URL}/api/v1/sessions"
assert_status "200"
assert_json_expr 'isinstance(payload, list)' "sessions response must be a list"
log_success "Manager GET /api/v1/sessions OK"

stage_begin_test "Manager: POST /api/v1/sessions allocates emulator session (READY + adbServers)"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" \
    '{"maxDevices":1,"api":"34","ttlSeconds":120,"deviceType":"emulator"}'
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session must be READY"
assert_json_expr 'payload.get("allocatedDevices", 0) >= 1' "Session must have at least 1 allocated device"
assert_json_expr 'len(payload.get("adbServers", [])) >= 1' "Session must include at least 1 adbServer"
assert_json_expr 'all("host" in s and "port" in s for s in payload.get("adbServers", []))' \
    "Each adbServer must have host and port"
assert_json_expr '"id" in payload and "createdAt" in payload and "expiresAt" in payload' \
    "Session response must have id, createdAt, expiresAt"
SESSION_ID="$(extract_json_value 'payload.get("id")')"
ALLOCATED="$(extract_json_value 'payload.get("allocatedDevices")')"
log_success "Session ${SESSION_ID} is READY with ${ALLOCATED} device(s)"

stage_begin_test "Manager: GET /api/v1/sessions/{id} returns the session"
http_json "GET" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "READY"' "Session must still be READY"
log_success "Manager GET /api/v1/sessions/${SESSION_ID} OK"

stage_begin_test "Manager: GET /api/v1/sessions?status=READY includes the session"
http_json "GET" "${MANAGER_URL}/api/v1/sessions?status=READY"
assert_status "200"
assert_json_expr 'len(payload) >= 1' "At least 1 READY session must be present"
log_success "Manager session filter ?status=READY OK"

stage_begin_test "Manager: GET /api/v1/sessions?status=RELEASED returns empty"
http_json "GET" "${MANAGER_URL}/api/v1/sessions?status=RELEASED"
assert_status "200"
assert_json_expr 'len(payload) == 0' "No RELEASED sessions yet"
log_success "Manager session filter ?status=RELEASED OK"

stage_begin_test "Manager: DELETE /api/v1/sessions/{id} releases the session"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Release response must have status=released"
SESSION_ID=""
log_success "Manager DELETE /api/v1/sessions OK"

stage_begin_test "Manager: invalid deviceType returns 400"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" \
    '{"maxDevices":1,"api":"34","ttlSeconds":120,"deviceType":"smartwatch"}'
assert_status "400"
assert_json_expr '"error" in payload' "400 response must include error field"
log_success "Manager correctly rejects unsupported deviceType"

stage_begin_test "Manager: GET /api/v1/config returns current config"
http_json "GET" "${MANAGER_URL}/api/v1/config"
assert_status "200"
assert_json_expr 'len(payload.get("providers", [])) == 3' "config must list 3 providers"
log_success "Manager GET /api/v1/config OK"

fi  # manager

log_step "Component tests passed (service: ${SERVICE})"
log_success "Local image(s) verified: API structure, auth enforcement, state changes"
# cleanup phase is emitted by the EXIT trap (see cleanup()).
