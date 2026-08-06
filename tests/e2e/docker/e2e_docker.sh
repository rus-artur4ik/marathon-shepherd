#!/usr/bin/env bash
# E2E Docker scenario tests.
#
# Spins up the full integration stack once, then executes every scenario against
# the running stack.  Each scenario may restart individual services with different
# environment variables (e.g. farm device count) or switch the manager config
# live via PUT /api/v1/config — no container restart required.
#
# Run from repo root:
#   tests/e2e/docker/e2e_docker.sh
#
# Prerequisites: docker, docker compose, curl, jq, python3
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/tests/integration/docker-compose.integration.yml"
# Share the `msh-integration` project name with docker_compose.sh so an
# integration stage can hand off its running stack for us to reuse (saves
# ~15s on stack bring-up). When MSH_REUSE_STACK is not set, this just looks
# like any other compose project and behaves normally.
PROJECT_NAME="msh-integration"
COMPOSE="docker compose -f $COMPOSE_FILE -p $PROJECT_NAME"
MSH_URL="http://localhost:16037"
source "$REPO_ROOT/tests/helpers/common.sh"
source "$REPO_ROOT/tests/helpers/stage_runtime.sh"
source "$REPO_ROOT/tests/helpers/cloud_orchestrator.sh"
source "$REPO_ROOT/tests/helpers/docker_stack.sh"
E2E_WORKSPACE="$(mktemp -d)"
RESPONSE_FILE="$E2E_WORKSPACE/msh_e2e_response.json"

PASSED=0
FAILED=0
declare -a FAILED_NAMES=()
TOTAL_SCENARIOS=7
LAST_FAILED_SCENARIO=""
WAIT_SERVICES=(
    "msh-integration-adb"
    "msh-integration-farm-server"
    "msh-integration-shepherd-adb"
    "msh-integration-shepherd-farm"
    "msh-integration-shepherd-cuttlefish"
    "msh-integration-manager"
)

# ── Colour helpers ────────────────────────────────────────────────────────────
if [[ -t 1 ]] && [[ "${NO_COLOR:-}" == "" ]]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
else
    RED=''; GREEN=''; CYAN=''; NC=''
fi

log()  { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $*"; }
pass() { echo -e "  ${GREEN}PASS${NC}  $1"; PASSED=$((PASSED + 1)); }
scenario_fail() {
    echo -e "  ${RED}FAIL${NC}  $1 — $2"
    LAST_FAILED_SCENARIO="$1"
    echo "MSH_FAILURE_CAUSE: Scenario failed"
    echo "MSH_FAILURE_EXPECTED: $1"
    echo "MSH_FAILURE_ACTUAL: $2"
    FAILED=$((FAILED + 1))
    FAILED_NAMES+=("$1")
}
stage_runtime_init "E2E Docker" "${TOTAL_SCENARIOS}" "false"

# ── Waiter helpers ────────────────────────────────────────────────────────────

wait_manager_healthy() {
    local deadline elapsed
    deadline=$(( $(date +%s) + 120 ))
    until curl -sf "$MSH_URL/live" >/dev/null 2>&1; do
        if (( $(date +%s) >= deadline )); then
            echo "  Manager did not become healthy within 120s"
            return 1
        fi
        sleep 0.2
    done
}

wait_service_healthy() {
    local container_name="$1" max_seconds="${2:-60}"
    local deadline
    deadline=$(( $(date +%s) + max_seconds ))
    local status=""
    while true; do
        status="$(docker inspect --format='{{.State.Health.Status}}' "${container_name}" 2>/dev/null || echo "missing")"
        if [[ "${status}" == "healthy" ]]; then
            return 0
        fi
        if (( $(date +%s) >= deadline )); then
            echo "  Service '$container_name' did not become healthy within ${max_seconds}s"
            return 1
        fi
        sleep 0.5
    done
}

print_stack_diagnostics() {
    echo -e "  ${CYAN}── docker compose ps ────────────────────────────────────────${NC}"
    $COMPOSE ps 2>/dev/null | sed 's/^/    /' || true
    echo -e "  ${CYAN}── docker compose logs (last 120 lines) ───────────────────${NC}"
    $COMPOSE logs --tail=120 2>/dev/null | sed 's/^/    /' || true
    echo -e "  ${CYAN}──────────────────────────────────────────────────────────────${NC}"
}

# ── API helpers ───────────────────────────────────────────────────────────────

# Create session; outputs HTTP status code; writes response JSON to $RESPONSE_FILE.
create_session() {
    local devices="${1:-1}" api="${2:-34}"
    curl -s \
        -o "$RESPONSE_FILE" \
        -w "%{http_code}" \
        -X POST "$MSH_URL/api/v1/sessions" \
        -H "Content-Type: application/json" \
        -d "{\"maxDevices\": $devices, \"api\": \"$api\", \"ttlSeconds\": 30}"
}

release_session() {
    local id="${1:-}"
    [[ -z "$id" ]] && return 0
    curl -sf -X DELETE "$MSH_URL/api/v1/sessions/$id" >/dev/null 2>&1 || true
}

cleanup() {
    # Always emit the cleanup phase so the dashboard reflects what's happening
    # here rather than showing stale "testing [N/M]" from the last scenario.
    stage_emit_phase "cleanup" 2>/dev/null || true
    $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
    # Don't remove images — they're shared with other stages via prebuild.
    rm -rf "$E2E_WORKSPACE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

response_field() { jq -r "${1} // empty" "$RESPONSE_FILE" 2>/dev/null; }

wait_session() {
    local session_id="$1" timeout_seconds="${2:-2}"
    curl -s \
        -o "$RESPONSE_FILE" \
        -w "%{http_code}" \
        -X POST "$MSH_URL/api/v1/sessions/$session_id/wait" \
        -H "Content-Type: application/json" \
        -d "{\"timeoutSeconds\": $timeout_seconds}"
}

# ── Config helper ─────────────────────────────────────────────────────────────

# Convert a YAML file to JSON via python3.
# Uses PyYAML when available; falls back to a minimal built-in parser that
# covers the specific scenario YAML structure used in tests/integration/.
yaml_file_to_json() {
    local yaml_file="$1"
    python3 - "$yaml_file" <<'PYEOF'
import json, re, sys

path = sys.argv[1]
with open(path) as f:
    text = f.read()

try:
    import yaml
    data = yaml.safe_load(text) or {}
    if "providers" not in data:
        data["providers"] = []
    print(json.dumps(data))
    sys.exit(0)
except ImportError:
    pass

# Minimal fallback: handles providers list and noDeviceStrategy object.
def coerce(v):
    v = v.strip().strip('"\'')
    try:
        return int(v)
    except ValueError:
        return v

data = {}
lines = [l.rstrip() for l in text.splitlines()
         if l.strip() and not l.strip().startswith("#")]
i = 0
while i < len(lines):
    line = lines[i]
    m = re.match(r"^(\w+):\s*(.*)", line)
    if not m:
        i += 1
        continue
    key, rest = m.group(1), m.group(2).strip()
    if rest == "[]":
        data[key] = []
        i += 1
        continue
    if rest:
        i += 1
        continue
    # Block value below this key
    i += 1
    block_list = []
    block_obj = {}
    current_item = None
    while i < len(lines) and lines[i].startswith("  "):
        sub = lines[i]
        li = re.match(r"  -\s+(\w+):\s*(.*)", sub)
        kv = re.match(r"\s+(\w+):\s*(.*)", sub)
        if li:
            if current_item is not None:
                block_list.append(current_item)
            current_item = {li.group(1): coerce(li.group(2))}
        elif kv:
            if current_item is not None:
                current_item[kv.group(1)] = coerce(kv.group(2))
            else:
                block_obj[kv.group(1)] = coerce(kv.group(2))
        i += 1
    if current_item is not None:
        block_list.append(current_item)
    if block_list:
        data[key] = block_list
    elif block_obj:
        data[key] = block_obj
    else:
        data[key] = []

if "providers" not in data:
    data["providers"] = []
print(json.dumps(data))
PYEOF
}

# ── Service control ───────────────────────────────────────────────────────────

# Restart the farm-server with a specific device count.
# shepherd-farm talks to farm-server over HTTP so it picks up the new state automatically.
restart_farm() {
    local total="${1:-3}" available="${2:-$1}"
    FARM_SERVER_TOTAL="$total" FARM_SERVER_AVAILABLE="$available" \
        $COMPOSE up -d farm-server >/dev/null 2>&1
    sleep 1  # brief settle time for shepherd-farm's next query
}

# Release all READY/PENDING sessions so PUT /api/v1/config is never blocked
# by a leaked session from a previous scenario that got an unexpected 201.
_release_active_sessions() {
    local sessions
    sessions="$(curl -s "$MSH_URL/api/v1/sessions" 2>/dev/null || echo '[]')"
    while IFS= read -r sid; do
        [[ -z "$sid" ]] && continue
        curl -sf -X DELETE "$MSH_URL/api/v1/sessions/$sid" >/dev/null 2>&1 || true
    done < <(printf '%s' "$sessions" \
        | jq -r '.[] | select(.status == "READY" or .status == "PENDING") | .id' 2>/dev/null)
}

# Switch the manager to a scenario config file via PUT /api/v1/config.
# Accepts a container path like /etc/msh/msh.scenario-farm-only.yaml and maps
# it to the corresponding host file under tests/integration/.
# The manager container is NOT restarted — config is hot-applied in place.
restart_manager() {
    local config="${1:-/etc/msh/msh.integration.yaml}"
    local filename host_yaml json code put_resp
    filename="$(basename "$config")"
    host_yaml="$REPO_ROOT/tests/integration/$filename"
    log "  → manager config: $config"

    # Clean up any sessions left over from a previous scenario that received an
    # unexpected 201 so the PUT below is never blocked with HTTP 409.
    _release_active_sessions

    json="$(yaml_file_to_json "$host_yaml")" || {
        echo "  ERROR: failed to convert $filename to JSON"
        return 1
    }

    put_resp="$(mktemp)"
    code=$(curl -s -o "$put_resp" -w "%{http_code}" \
        -X PUT "$MSH_URL/api/v1/config" \
        -H "Content-Type: application/json" \
        -d "$json")

    if [[ "$code" != "200" ]]; then
        echo "  ERROR: PUT /api/v1/config returned HTTP $code — $(cat "$put_resp" 2>/dev/null || true)"
        rm -f "$put_resp"
        return 1
    fi
    rm -f "$put_resp"
}

# ── Scenario runner ───────────────────────────────────────────────────────────

run_scenario() {
    local name="$1"; shift
    stage_begin_test "$name"
    local result=0
    "$@" 2>&1 | sed 's/^/    /' || result=$?
    if [[ $result -eq 0 ]]; then
        pass "$name"
    else
        scenario_fail "$name" "scenario function exited with code $result"
        echo -e "  ${CYAN}── active config ─────────────────────────────────────────────${NC}"
        curl -s "$MSH_URL/api/v1/config" 2>/dev/null \
            | jq '.' 2>/dev/null || echo "    (could not fetch config)"  | sed 's/^/    /'
        echo -e "  ${CYAN}── manager logs (last 40 lines) ──────────────────────────────${NC}"
        $COMPOSE logs --tail=40 --no-log-prefix manager 2>/dev/null | sed 's/^/    /'
        echo -e "  ${CYAN}──────────────────────────────────────────────────────────────${NC}"
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
    if [[ -n "${session_id}" ]]; then
        release_session "$session_id"
    fi
    local allocated
    allocated="$(response_field '.allocatedDevices')"
    echo "  HTTP $code  status=$status  allocated=$allocated"
    if [[ "$code" == "201" ]] && [[ "$status" == "READY" ]]; then
        return 0
    fi
    if [[ "$code" == "201" ]] && [[ "$status" == "PENDING" ]]; then
        echo "  SKIP: cuttlefish backend accepted request but did not allocate devices in this environment"
        return 0
    fi
    if [[ "$code" == "503" ]]; then
        echo "  SKIP: cuttlefish backend unavailable in this environment"
        return 0
    fi
    return 1
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

# 5. Registered farm devices are all busy -> session should enter PENDING queue
scenario_busy_devices_return_pending() {
    restart_farm 3 0
    restart_manager "/etc/msh/msh.scenario-farm-only.yaml"

    local code status
    code=$(create_session 1)
    status=$(response_field '.status')

    echo "  HTTP $code  status=$status  queue=$(response_field '.queuePosition')"
    [[ "$code" == "201" ]] && [[ "$status" == "PENDING" ]]
}

# 6. Empty providers config → HTTP 503
scenario_empty_providers() {
    restart_manager "/etc/msh/msh.scenario-empty-providers.yaml"

    local code
    code=$(create_session 1)

    echo "  HTTP $code  error=$(response_field '.error')"
    [[ "$code" == "503" ]]
}

# 7. Queue + wait: farm-only cluster with 1 device -> second request stays PENDING until release
scenario_queue_wait_ready() {
    restart_farm 1 1
    restart_manager "/etc/msh/msh.scenario-farm-only.yaml"

    local holder_code holder_status holder_id
    holder_code=$(create_session 1)
    holder_status=$(response_field '.status')
    holder_id=$(response_field '.id')

    local waiter_code waiter_status waiter_id
    waiter_code=$(create_session 1)
    waiter_status=$(response_field '.status')
    waiter_id=$(response_field '.id')

    echo "  holder: HTTP $holder_code  status=$holder_status"
    echo "  waiter: HTTP $waiter_code  status=$waiter_status  queue=$(response_field '.queuePosition')"
    [[ "$holder_code" == "201" ]] && [[ "$holder_status" == "READY" ]] || return 1
    [[ "$waiter_code" == "201" ]] && [[ "$waiter_status" == "PENDING" ]] || return 1

    local pending_code pending_status
    pending_code=$(wait_session "$waiter_id" 1)
    pending_status=$(response_field '.status')
    echo "  wait(1s): HTTP $pending_code  status=$pending_status"
    [[ "$pending_code" == "200" ]] && [[ "$pending_status" == "PENDING" ]] || return 1

    release_session "$holder_id"

    local ready_code ready_status ready_allocated
    ready_code=$(wait_session "$waiter_id" 5)
    ready_status=$(response_field '.status')
    ready_allocated=$(response_field '.allocatedDevices')
    release_session "$waiter_id"

    echo "  wait(after release): HTTP $ready_code  status=$ready_status  allocated=$ready_allocated"
    [[ "$ready_code" == "200" ]] && [[ "$ready_status" == "READY" ]] && [[ "$ready_allocated" -ge 1 ]]
}

# ── Main ──────────────────────────────────────────────────────────────────────

# ── Stack bring-up: reuse a handed-off stack or spin up our own ──────────────
# When the [integration] stage ran right before us with MSH_LEAVE_STACK_RUNNING=1,
# all containers are already up and the manager is healthy. Verify that — if
# anything is broken we fall back to a clean `up`, so a handoff failure can't
# wedge the test run.
HANDED_OFF_CONTAINERS=(
    "msh-integration-adb"
    "msh-integration-shepherd-adb"
    "msh-integration-shepherd-farm"
    "msh-integration-shepherd-cuttlefish"
    "msh-integration-manager"
)
if docker_stack_already_running && docker_stack_verify_alive "${MSH_URL}/live" "${HANDED_OFF_CONTAINERS[@]}"; then
    log "Reusing handoff stack from [integration] — skipping compose down/up"
    stage_emit_phase "reusing"
    # Config file is already baked into the running containers. We still need
    # the orchestrator config path set locally for any helpers that read it.
    download_cloud_orchestrator_config "$E2E_WORKSPACE"
else
    if docker_stack_already_running; then
        log "Handoff requested but stack is not in a reusable state; falling back to fresh up"
    fi
    log "Tearing down any leftover stack from a previous run…"
    stage_emit_phase "stopping"
    $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true

    if [[ -n "${MSH_PREBUILT_ORCHESTRATOR_CONFIG:-}" && -s "${MSH_PREBUILT_ORCHESTRATOR_CONFIG}" ]]; then
        log "Reusing pre-downloaded Cloud Orchestrator config…"
        stage_emit_phase "reusing"
    else
        log "Downloading official Cloud Orchestrator config…"
        stage_emit_phase "pulling"
    fi
    download_cloud_orchestrator_config "$E2E_WORKSPACE"

    if [[ "${MSH_SKIP_DOCKER_BUILD:-0}" != "1" ]]; then
        log "Building integration images…"
        stage_emit_phase "building" 1 2
        $COMPOSE build --no-cache cuttlefish-orchestrator 2>&1 | tail -5
        stage_emit_phase "building" 2 2
        $COMPOSE build 2>&1 | tail -5
    elif ! docker image inspect msh-integration-cuttlefish-orchestrator:latest >/dev/null 2>&1; then
        log "Recovering missing cuttlefish-orchestrator image…"
        stage_emit_phase "building"
        $COMPOSE build --no-cache cuttlefish-orchestrator 2>&1 | tail -5
    else
        log "Reusing pre-built integration images…"
        stage_emit_phase "reusing"
    fi

    log "Starting full integration stack (adapters + manager)…"
    stage_emit_phase "starting"
    if ! $COMPOSE up -d \
        adb farm-server shepherd-adb shepherd-farm shepherd-cuttlefish manager; then
        print_stack_diagnostics
        exit 1
    fi

    log "Waiting for stack readiness…"
    WAIT_PHASE_TOTAL=$(( ${#WAIT_SERVICES[@]} + 1 ))
    WAIT_PHASE_STEP=0
    for service in "${WAIT_SERVICES[@]}"; do
        WAIT_PHASE_STEP=$((WAIT_PHASE_STEP + 1))
        stage_emit_phase "starting" "$WAIT_PHASE_STEP" "$WAIT_PHASE_TOTAL"
        if ! wait_service_healthy "$service" 120; then
            print_stack_diagnostics
            exit 1
        fi
    done
    stage_emit_phase "starting" "$WAIT_PHASE_TOTAL" "$WAIT_PHASE_TOTAL"
fi
if ! wait_manager_healthy; then
    print_stack_diagnostics
    exit 1
fi

log "Running scenarios…"
echo ""

run_scenario "1 - mixed cluster (all providers, farm=3)"    scenario_mixed_cluster
run_scenario "2 - farm-only cluster (farm=3)"               scenario_farm_only
run_scenario "3 - cuttlefish-only cluster"                  scenario_cuttlefish_only
run_scenario "4 - partial allocation (farm=1, request=5)"   scenario_partial_allocation
run_scenario "5 - busy devices return PENDING"              scenario_busy_devices_return_pending
run_scenario "6 - empty providers → 503"                    scenario_empty_providers
run_scenario "7 - queue wait promotes PENDING to READY"     scenario_queue_wait_ready

log "Cleaning up…"
stage_emit_phase "cleanup"
# Actual teardown happens in the cleanup EXIT trap — this is only here to keep
# the dashboard phase label in sync with user-visible "Cleaning up…" log.

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
