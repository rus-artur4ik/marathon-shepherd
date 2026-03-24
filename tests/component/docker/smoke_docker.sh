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
source "${REPO_ROOT}/tests/helpers/common.sh"

SERVICE="${MSH_COMPONENT_SERVICE:-all}"
case "${SERVICE}" in
    shepherd-adb)
        COMPOSE_SERVICES=("adb" "shepherd-adb")
        HEALTH_CONTAINERS=("msh-smoke-shepherd-adb")
        ;;
    shepherd-farm)
        COMPOSE_SERVICES=("farm-server" "shepherd-farm")
        HEALTH_CONTAINERS=("msh-smoke-shepherd-farm")
        ;;
    shepherd-cuttlefish)
        COMPOSE_SERVICES=("shepherd-cuttlefish")
        HEALTH_CONTAINERS=("msh-smoke-shepherd-cuttlefish")
        ;;
    manager)
        COMPOSE_SERVICES=()
        HEALTH_CONTAINERS=("msh-smoke-shepherd-adb" "msh-smoke-shepherd-farm" "msh-smoke-shepherd-cuttlefish" "msh-smoke-manager")
        ;;
    all)
        COMPOSE_SERVICES=()
        HEALTH_CONTAINERS=("msh-smoke-shepherd-adb" "msh-smoke-shepherd-farm" "msh-smoke-shepherd-cuttlefish" "msh-smoke-manager")
        ;;
    *)
        echo "Unknown MSH_COMPONENT_SERVICE: ${SERVICE}" >&2
        echo "Valid values: shepherd-adb, shepherd-farm, shepherd-cuttlefish, manager, all" >&2
        exit 1
        ;;
esac

set_log_context "Component ${SERVICE}"
enable_dynamic_logs

ADB_ADAPTER_URL="http://127.0.0.1:17037"
FARM_ADAPTER_URL="http://127.0.0.1:17038"
CUTTLEFISH_ADAPTER_URL="http://127.0.0.1:17039"
MANAGER_URL="http://127.0.0.1:16037"
ADB_SECRET="smoke-secret-adb"
FARM_SECRET="smoke-secret-farm"
CUTTLEFISH_SECRET="smoke-secret-cuttlefish"
SESSION_ID=""
FARM_LEASE_ID=""
CVDR_LEASE_ID=""

cleanup() {
    if [[ -n "${SESSION_ID}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}" || true
    fi
    docker compose -f "${COMPOSE_FILE}" down --volumes --remove-orphans >/dev/null 2>&1 || true
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

# ── Prerequisites ────────────────────────────────────────────────────────────

log_step "Checking prerequisites"
require_command docker
require_command curl
require_command python3
if ! docker compose version >/dev/null 2>&1; then
    fail "docker compose v2 is required (not the standalone docker-compose)"
fi

# ── Stack startup ─────────────────────────────────────────────────────────────

log_step "Building and starting stack (service: ${SERVICE})"
if (( ${#COMPOSE_SERVICES[@]} > 0 )); then
    docker compose -f "${COMPOSE_FILE}" up -d --build --remove-orphans "${COMPOSE_SERVICES[@]}"
else
    docker compose -f "${COMPOSE_FILE}" up -d --build --remove-orphans
fi

log_step "Waiting for services to become healthy (max 120s)"
DEADLINE=$(( $(date +%s) + 120 ))
while true; do
    ALL_HEALTHY=true
    for svc in "${HEALTH_CONTAINERS[@]}"; do
        STATUS="$(docker inspect --format='{{.State.Health.Status}}' "${svc}" 2>/dev/null || echo "missing")"
        if [[ "${STATUS}" != "healthy" ]]; then
            ALL_HEALTHY=false
            break
        fi
    done
    if [[ "${ALL_HEALTHY}" == "true" ]]; then
        break
    fi
    if (( $(date +%s) >= DEADLINE )); then
        docker compose -f "${COMPOSE_FILE}" logs --tail=50 >&2 || true
        fail "Services did not become healthy within 120 seconds"
    fi
    sleep 2
done
log_success "Services healthy"

# ── shepherd-adb ─────────────────────────────────────────────────────────────

if [[ "${SERVICE}" == "shepherd-adb" || "${SERVICE}" == "all" ]]; then

log_step "shepherd-adb: /health is public and returns adapterType=adb"
http_json "GET" "${ADB_ADAPTER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("adapterType") == "adb"' "adapterType must be adb"
assert_json_expr '"status" in payload' "health response must have status field"
assert_json_expr '"version" in payload' "health response must have version field"
log_success "shepherd-adb /health OK"

log_step "shepherd-adb: /status rejects unauthenticated requests"
http_json "GET" "${ADB_ADAPTER_URL}/status"
assert_status "401"
log_success "shepherd-adb /status correctly requires auth"

log_step "shepherd-adb: /status returns pool, access, inventory, capabilities"
http_json_bearer "GET" "${ADB_ADAPTER_URL}/status" "${ADB_SECRET}"
assert_status "200"
assert_json_expr '"pool" in payload' "status must have pool"
assert_json_expr '"access" in payload' "status must have access"
assert_json_expr '"inventory" in payload' "status must have inventory"
assert_json_expr '"capabilities" in payload' "status must have capabilities"
assert_json_expr '"physical" in payload.get("capabilities", {}).get("supportedDeviceTypes", [])' \
    "adb adapter must advertise physical device support"
log_success "shepherd-adb /status returns valid structure"

log_step "shepherd-adb: /acquire returns 503 when no physical devices connected"
http_json_bearer "POST" "${ADB_ADAPTER_URL}/acquire" "${ADB_SECRET}" \
    '{"count":1,"apiLevel":"34","ttlSeconds":120}'
assert_status "503"
assert_json_expr '"error" in payload' "503 response must include error field"
log_success "shepherd-adb /acquire correctly returns 503 (no USB devices in container)"

log_step "shepherd-adb: /acquire rejects unauthenticated requests"
http_json "POST" "${ADB_ADAPTER_URL}/acquire" '{"count":1,"apiLevel":"34","ttlSeconds":120}'
assert_status "401"
log_success "shepherd-adb /acquire correctly requires auth"

fi  # shepherd-adb

# ── shepherd-farm ─────────────────────────────────────────────────────────────

if [[ "${SERVICE}" == "shepherd-farm" || "${SERVICE}" == "all" ]]; then

log_step "shepherd-farm: /health is public and returns adapterType=farm"
http_json "GET" "${FARM_ADAPTER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("adapterType") == "farm"' "adapterType must be farm"
assert_json_expr 'payload.get("status") == "healthy"' "farm adapter must be healthy (farm-server is running)"
log_success "shepherd-farm /health OK"

log_step "shepherd-farm: /status rejects unauthenticated requests"
http_json "GET" "${FARM_ADAPTER_URL}/status"
assert_status "401"
log_success "shepherd-farm /status correctly requires auth"

log_step "shepherd-farm: /status shows 3 total emulators and emulator support"
http_json_bearer "GET" "${FARM_ADAPTER_URL}/status" "${FARM_SECRET}"
assert_status "200"
assert_json_expr 'payload.get("pool", {}).get("total", 0) == 3' \
    "farm pool must have 3 total emulators (FARM_SERVER_TOTAL=3)"
assert_json_expr '"emulator" in payload.get("capabilities", {}).get("supportedDeviceTypes", [])' \
    "farm adapter must advertise emulator support"
log_success "shepherd-farm /status returns valid structure"

log_step "shepherd-farm: /acquire allocates 2 emulators and returns leaseId"
http_json_bearer "POST" "${FARM_ADAPTER_URL}/acquire" "${FARM_SECRET}" \
    '{"count":2,"apiLevel":"34","ttlSeconds":120}'
assert_status "200"
assert_json_expr 'payload.get("acquiredCount") == 2' "farm must acquire exactly 2 emulators"
assert_json_expr 'payload.get("leaseId") not in (None, "")' "farm must return a non-empty leaseId"
assert_json_expr '"access" in payload' "farm acquire response must have access"
FARM_LEASE_ID="$(extract_json_value 'payload.get("leaseId")')"
log_success "shepherd-farm allocated 2 emulators (leaseId=${FARM_LEASE_ID})"

log_step "shepherd-farm: /release returns leased emulators"
http_json_bearer "DELETE" "${FARM_ADAPTER_URL}/release/${FARM_LEASE_ID}" "${FARM_SECRET}"
assert_status "200"
FARM_LEASE_ID=""
log_success "shepherd-farm /release OK"

log_step "shepherd-farm: /status reflects released emulators"
http_json_bearer "GET" "${FARM_ADAPTER_URL}/status" "${FARM_SECRET}"
assert_status "200"
assert_json_expr 'payload.get("pool", {}).get("available", 0) == 3' \
    "all 3 emulators must be available after release"
log_success "shepherd-farm pool state is consistent after release"

fi  # shepherd-farm

# ── shepherd-cuttlefish ───────────────────────────────────────────────────────

if [[ "${SERVICE}" == "shepherd-cuttlefish" || "${SERVICE}" == "all" ]]; then

log_step "shepherd-cuttlefish: /health is public and returns adapterType=cuttlefish"
http_json "GET" "${CUTTLEFISH_ADAPTER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("adapterType") == "cuttlefish"' "adapterType must be cuttlefish"
assert_json_expr 'payload.get("status") == "healthy"' "cuttlefish adapter must be healthy"
log_success "shepherd-cuttlefish /health OK"

log_step "shepherd-cuttlefish: /status rejects unauthenticated requests"
http_json "GET" "${CUTTLEFISH_ADAPTER_URL}/status"
assert_status "401"
log_success "shepherd-cuttlefish /status correctly requires auth"

log_step "shepherd-cuttlefish: /status shows 2 initial instances"
http_json_bearer "GET" "${CUTTLEFISH_ADAPTER_URL}/status" "${CUTTLEFISH_SECRET}"
assert_status "200"
assert_json_expr 'payload.get("pool", {}).get("total", 0) >= 2' \
    "cuttlefish pool must have at least 2 instances (CVDR_INITIAL_INSTANCES=2)"
assert_json_expr '"emulator" in payload.get("capabilities", {}).get("supportedDeviceTypes", [])' \
    "cuttlefish adapter must advertise emulator support"
log_success "shepherd-cuttlefish /status returns valid structure"

log_step "shepherd-cuttlefish: /acquire creates an instance and returns leaseId"
http_json_bearer "POST" "${CUTTLEFISH_ADAPTER_URL}/acquire" "${CUTTLEFISH_SECRET}" \
    '{"count":1,"apiLevel":"34","ttlSeconds":120}'
assert_status "200"
assert_json_expr 'payload.get("acquiredCount") == 1' "cuttlefish must acquire exactly 1 instance"
assert_json_expr 'payload.get("leaseId") not in (None, "")' "cuttlefish must return a non-empty leaseId"
assert_json_expr '"access" in payload' "cuttlefish acquire response must have access"
CVDR_LEASE_ID="$(extract_json_value 'payload.get("leaseId")')"
log_success "shepherd-cuttlefish acquired 1 instance (leaseId=${CVDR_LEASE_ID})"

log_step "shepherd-cuttlefish: /release stops the instance"
http_json_bearer "DELETE" "${CUTTLEFISH_ADAPTER_URL}/release/${CVDR_LEASE_ID}" "${CUTTLEFISH_SECRET}"
assert_status "200"
CVDR_LEASE_ID=""
log_success "shepherd-cuttlefish /release OK"

fi  # shepherd-cuttlefish

# ── Manager ───────────────────────────────────────────────────────────────────

if [[ "${SERVICE}" == "manager" || "${SERVICE}" == "all" ]]; then

log_step "Manager: /live liveness endpoint"
http_json "GET" "${MANAGER_URL}/live"
assert_status "200"
log_success "Manager /live OK"

log_step "Manager: /health shows all 3 providers healthy"
http_json "GET" "${MANAGER_URL}/health"
assert_status "200"
assert_json_expr 'payload.get("status") == "healthy"' "Manager overall status must be healthy"
assert_json_expr 'len(payload.get("providers", [])) == 3' "Manager must expose 3 providers"
assert_json_expr 'all(p.get("status") == "HEALTHY" for p in payload.get("providers", []))' \
    "All 3 providers must be HEALTHY"
log_success "Manager /health: 3 providers all HEALTHY"

log_step "Manager: /api/v1/devices shows inventory from all providers"
http_json "GET" "${MANAGER_URL}/api/v1/devices"
assert_status "200"
assert_json_expr 'len(payload.get("providers", [])) == 3' "devices endpoint must list 3 providers"
assert_json_expr 'any("emulator" in p.get("capabilities", {}).get("supportedDeviceTypes", []) for p in payload.get("providers", []))' \
    "at least one provider must support emulator"
assert_json_expr 'any("physical" in p.get("capabilities", {}).get("supportedDeviceTypes", []) for p in payload.get("providers", []))' \
    "at least one provider must support physical"
log_success "Manager /api/v1/devices OK"

log_step "Manager: GET /api/v1/sessions returns empty list before any session"
http_json "GET" "${MANAGER_URL}/api/v1/sessions"
assert_status "200"
assert_json_expr 'isinstance(payload, list)' "sessions response must be a list"
log_success "Manager GET /api/v1/sessions OK"

log_step "Manager: POST /api/v1/sessions allocates emulator session (READY + adbServers)"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" \
    '{"devices":1,"apiLevel":"34","ttlSeconds":120,"deviceType":"emulator"}'
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

log_step "Manager: GET /api/v1/sessions/{id} returns the session"
http_json "GET" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "READY"' "Session must still be READY"
log_success "Manager GET /api/v1/sessions/${SESSION_ID} OK"

log_step "Manager: GET /api/v1/sessions?status=READY includes the session"
http_json "GET" "${MANAGER_URL}/api/v1/sessions?status=READY"
assert_status "200"
assert_json_expr 'len(payload) >= 1' "At least 1 READY session must be present"
log_success "Manager session filter ?status=READY OK"

log_step "Manager: GET /api/v1/sessions?status=RELEASED returns empty"
http_json "GET" "${MANAGER_URL}/api/v1/sessions?status=RELEASED"
assert_status "200"
assert_json_expr 'len(payload) == 0' "No RELEASED sessions yet"
log_success "Manager session filter ?status=RELEASED OK"

log_step "Manager: DELETE /api/v1/sessions/{id} releases the session"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Release response must have status=released"
SESSION_ID=""
log_success "Manager DELETE /api/v1/sessions OK"

log_step "Manager: invalid deviceType returns 400"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" \
    '{"devices":1,"apiLevel":"34","ttlSeconds":120,"deviceType":"smartwatch"}'
assert_status "400"
assert_json_expr '"error" in payload' "400 response must include error field"
log_success "Manager correctly rejects unsupported deviceType"

log_step "Manager: GET /api/v1/config returns current config"
http_json "GET" "${MANAGER_URL}/api/v1/config"
assert_status "200"
assert_json_expr 'len(payload.get("providers", [])) == 3' "config must list 3 providers"
log_success "Manager GET /api/v1/config OK"

fi  # manager

log_step "Component tests passed (service: ${SERVICE})"
log_success "Local image(s) verified: API structure, auth enforcement, state changes"
