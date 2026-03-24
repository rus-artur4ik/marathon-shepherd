#!/usr/bin/env bash
# E2E Docker scenario tests.
#
# Spins up the full integration stack once, then executes every scenario against
# the running stack.  Each scenario may restart individual services with different
# environment variables (e.g. farm device count, manager config file).
#
# Run from repo root:
#   tests/e2e/docker/e2e_docker.sh
#
# Prerequisites: docker, docker compose, curl, jq
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/tests/integration/docker-compose.integration.yml"
COMPOSE="docker compose -f $COMPOSE_FILE"
MSH_URL="http://localhost:16037"

PASSED=0
FAILED=0
declare -a FAILED_NAMES=()

# ── Colour helpers ────────────────────────────────────────────────────────────
if [[ -t 1 ]] && [[ "${NO_COLOR:-}" == "" ]]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
else
    RED=''; GREEN=''; CYAN=''; NC=''
fi

log()  { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $*"; }
pass() { echo -e "  ${GREEN}PASS${NC}  $1"; PASSED=$((PASSED + 1)); }
fail() { echo -e "  ${RED}FAIL${NC}  $1 — $2"; FAILED=$((FAILED + 1)); FAILED_NAMES+=("$1"); }

# ── Waiter helpers ────────────────────────────────────────────────────────────

wait_manager_healthy() {
    local max_seconds=120 elapsed=0
    until curl -sf "$MSH_URL/live" >/dev/null 2>&1; do
        elapsed=$((elapsed + 2))
        if [[ $elapsed -ge $max_seconds ]]; then
            echo "  Manager did not become healthy within ${max_seconds}s"
            return 1
        fi
        sleep 2
    done
}

wait_service_healthy() {
    local service="$1" max_seconds="${2:-60}"
    local elapsed=0
    until $COMPOSE ps --status healthy "$service" 2>/dev/null | grep -q healthy; do
        elapsed=$((elapsed + 2))
        if [[ $elapsed -ge $max_seconds ]]; then
            echo "  Service '$service' did not become healthy within ${max_seconds}s"
            return 1
        fi
        sleep 2
    done
}

# ── API helpers ───────────────────────────────────────────────────────────────

# Create session; outputs HTTP status code; writes response JSON to $RESPONSE_FILE.
RESPONSE_FILE="/tmp/msh_e2e_response.json"
create_session() {
    local devices="${1:-1}" api="${2:-34}"
    curl -s \
        -o "$RESPONSE_FILE" \
        -w "%{http_code}" \
        -X POST "$MSH_URL/api/v1/sessions" \
        -H "Content-Type: application/json" \
        -d "{\"devices\": $devices, \"apiLevel\": \"$api\", \"ttlSeconds\": 30}"
}

release_session() {
    local id="${1:-}"
    [[ -z "$id" ]] && return 0
    curl -sf -X DELETE "$MSH_URL/api/v1/sessions/$id" >/dev/null 2>&1 || true
}

response_field() { jq -r "${1} // empty" "$RESPONSE_FILE" 2>/dev/null; }

# ── Service control ───────────────────────────────────────────────────────────

# Restart the farm-server with a specific device count.
# shepherd-farm talks to farm-server over HTTP so it picks up the new state automatically.
restart_farm() {
    local total="${1:-3}" available="${2:-$1}"
    FARM_SERVER_TOTAL="$total" FARM_SERVER_AVAILABLE="$available" \
        $COMPOSE up -d farm-server >/dev/null 2>&1
    sleep 1  # brief settle time for shepherd-farm's next query
}

# Restart the manager with a specific scenario config file (path inside the container).
restart_manager() {
    local config="${1:-/etc/msh/msh.integration.yaml}"
    log "  → manager config: $config"
    MSH_SCENARIO_CONFIG="$config" $COMPOSE up -d manager >/dev/null 2>&1
    wait_manager_healthy
}

# ── Scenario runner ───────────────────────────────────────────────────────────

run_scenario() {
    local name="$1"; shift
    log "Scenario: $name"
    local result=0
    "$@" 2>&1 | sed 's/^/    /' || result=$?
    if [[ $result -eq 0 ]]; then
        pass "$name"
    else
        fail "$name" "scenario function exited with code $result"
    fi
    return 0  # never propagate — run all scenarios even after failures
}

# ── Scenarios ─────────────────────────────────────────────────────────────────

# 1. Mixed cluster: all 3 providers configured, farm has 3 devices → READY
scenario_mixed_cluster() {
    restart_farm 3 3
    restart_manager "/etc/msh/msh.integration.yaml"

    local code status session_id
    code=$(create_session 2)
    status=$(response_field '.status')
    session_id=$(response_field '.id')
    release_session "$session_id"

    echo "  HTTP $code  status=$status  allocated=$(response_field '.allocatedDevices')"
    [[ "$code" == "201" ]] && [[ "$status" == "READY" ]]
}

# 2. Farm-only cluster: only farm provider, 3 devices → READY
scenario_farm_only() {
    restart_farm 3 3
    restart_manager "/etc/msh/msh.scenario-farm-only.yaml"

    local code status session_id
    code=$(create_session 1)
    status=$(response_field '.status')
    session_id=$(response_field '.id')
    release_session "$session_id"

    echo "  HTTP $code  status=$status  allocated=$(response_field '.allocatedDevices')"
    [[ "$code" == "201" ]] && [[ "$status" == "READY" ]]
}

# 3. Cuttlefish-only cluster: only cuttlefish provider (2 fake instances) → READY
scenario_cuttlefish_only() {
    restart_manager "/etc/msh/msh.scenario-cuttlefish-only.yaml"

    local code status session_id
    code=$(create_session 1)
    status=$(response_field '.status')
    session_id=$(response_field '.id')
    release_session "$session_id"

    echo "  HTTP $code  status=$status  allocated=$(response_field '.allocatedDevices')"
    [[ "$code" == "201" ]] && [[ "$status" == "READY" ]]
}

# 4. Partial allocation: farm has 1 device, request 5 → READY with partial count
scenario_partial_allocation() {
    restart_farm 1 1
    restart_manager "/etc/msh/msh.scenario-farm-only.yaml"

    local code status allocated session_id
    code=$(create_session 5)
    status=$(response_field '.status')
    allocated=$(response_field '.allocatedDevices')
    session_id=$(response_field '.id')
    release_session "$session_id"

    echo "  HTTP $code  status=$status  allocated=$allocated (requested=5)"
    # Partial allocation is still a READY session with fewer devices than requested
    [[ "$code" == "201" ]] && [[ "$status" == "READY" ]] && [[ "$allocated" -lt 5 ]]
}

# 5. No devices available (farm=0) → HTTP 503
scenario_no_devices_fail_immediately() {
    restart_farm 3 0
    restart_manager "/etc/msh/msh.scenario-farm-only.yaml"

    local code
    code=$(create_session 1)

    echo "  HTTP $code  error=$(response_field '.error')"
    [[ "$code" == "503" ]]
}

# 6. Empty providers config → HTTP 503
scenario_empty_providers() {
    restart_manager "/etc/msh/msh.scenario-empty-providers.yaml"

    local code
    code=$(create_session 1)

    echo "  HTTP $code  error=$(response_field '.error')"
    [[ "$code" == "503" ]]
}

# 7. WAIT_WITH_TIMEOUT strategy, no devices available → 503 after configured timeout
scenario_wait_timeout_expires() {
    restart_farm 3 0
    restart_manager "/etc/msh/msh.scenario-wait-strategy.yaml"

    log "  → waiting up to 20s for WAIT strategy to expire (timeout=15s in config)…"
    local code start elapsed
    start=$SECONDS
    code=$(create_session 1)
    elapsed=$((SECONDS - start))

    echo "  HTTP $code  elapsed=${elapsed}s  error=$(response_field '.error')"
    [[ "$code" == "503" ]] && [[ "$elapsed" -ge 14 ]]
}

# ── Main ──────────────────────────────────────────────────────────────────────

log "Building integration images…"
$COMPOSE build 2>&1 | tail -5

log "Starting infrastructure services (adb, farm-server, adapters)…"
$COMPOSE up -d --wait \
    adb farm-server shepherd-adb shepherd-farm shepherd-cuttlefish 2>&1 | tail -5

# Clean slate for the manager state volume
$COMPOSE rm -sf manager >/dev/null 2>&1 || true
docker volume rm "$(basename "$REPO_ROOT")_msh-integration-data" >/dev/null 2>&1 || true

log "Running scenarios…"
echo ""

run_scenario "1 - mixed cluster (all providers, farm=3)"    scenario_mixed_cluster
run_scenario "2 - farm-only cluster (farm=3)"               scenario_farm_only
run_scenario "3 - cuttlefish-only cluster"                  scenario_cuttlefish_only
run_scenario "4 - partial allocation (farm=1, request=5)"   scenario_partial_allocation
run_scenario "5 - no devices → FAIL_IMMEDIATELY (503)"      scenario_no_devices_fail_immediately
run_scenario "6 - empty providers → 503"                    scenario_empty_providers
run_scenario "7 - WAIT_WITH_TIMEOUT strategy expires (503)" scenario_wait_timeout_expires

log "Cleaning up…"
$COMPOSE down -v >/dev/null 2>&1

echo ""
echo "========================================================"
if [[ $FAILED -eq 0 ]]; then
    echo -e "${GREEN}All $PASSED scenario(s) passed.${NC}"
else
    echo -e "${RED}$FAILED of $((PASSED + FAILED)) scenario(s) FAILED:${NC}"
    for name in "${FAILED_NAMES[@]}"; do
        echo "  - $name"
    done
    exit 1
fi
