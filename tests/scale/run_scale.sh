#!/usr/bin/env bash
# Scale test harness — brings up fake adapters + real manager, then drives load.
#
# Modes (SCALE_MODE env):
#   smoke   — 60s at 20 RPS, small pool, used in CI           (default in CI)
#   full    — 300s at 100 RPS, big pool, used nightly
#
# Load driver:
#   - Default: built-in python_churn (no external deps beyond python3).
#   - Opt into k6 by setting MSH_USE_K6=1 (the runner maps `--k6` to that env).
#     When MSH_USE_K6=1 and k6 isn't on PATH, the script fails fast with an
#     install hint — no silent fallback.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.scale.yml"
PROJECT_NAME="msh-scale"
MANAGER_URL="http://localhost:16037"

# Emit dashboard phase markers so the runner shows "starting"/"testing"/"cleanup"
# instead of a bare "running" counter. Defined here to avoid pulling the full
# stage_runtime helper (which depends on common.sh logging setup) into this
# standalone script.
msh_emit_phase() {
    local phase="$1"
    local cur="${2:-}"
    local total="${3:-}"
    if [[ -n "${cur}" && -n "${total}" ]]; then
        echo "MSH_PHASE: ${phase} [${cur}/${total}]"
    else
        echo "MSH_PHASE: ${phase}"
    fi
}
msh_emit_progress() {
    echo "MSH_TEST_PROGRESS: ${1}/${2}"
}

SCALE_MODE="${SCALE_MODE:-smoke}"
case "${SCALE_MODE}" in
    smoke)
        export FAKE_RACK_A_DEVICES="${FAKE_RACK_A_DEVICES:-30}"
        export FAKE_RACK_B_DEVICES="${FAKE_RACK_B_DEVICES:-30}"
        export FAKE_RACK_C_DEVICES="${FAKE_RACK_C_DEVICES:-15}"
        export K6_TARGET_RPS="${K6_TARGET_RPS:-20}"
        export K6_DURATION="${K6_DURATION:-30s}"
        ;;
    full)
        export FAKE_RACK_A_DEVICES="${FAKE_RACK_A_DEVICES:-200}"
        export FAKE_RACK_B_DEVICES="${FAKE_RACK_B_DEVICES:-200}"
        export FAKE_RACK_C_DEVICES="${FAKE_RACK_C_DEVICES:-100}"
        export K6_TARGET_RPS="${K6_TARGET_RPS:-100}"
        export K6_DURATION="${K6_DURATION:-300s}"
        ;;
    *)
        echo "Unknown SCALE_MODE='${SCALE_MODE}'. Expected smoke|full." >&2
        exit 2
        ;;
esac

cleanup() {
    local exit_code=$?
    msh_emit_phase "cleanup"
    printf '[%s] Tearing down scale stack\n' "$(date +%H:%M:%S)"
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" down -v --remove-orphans >/dev/null 2>&1 || true
    exit "${exit_code}"
}
trap cleanup EXIT INT TERM

stamped() {
    # Prefix a line with [HH:MM:SS] so the dashboard's Output tail shows a
    # coherent timeline — matches the format used by other shell harnesses.
    printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"
}

stamped "Starting scale stack (mode=${SCALE_MODE})"
stamped "  devices:  rack-a=${FAKE_RACK_A_DEVICES}  rack-b=${FAKE_RACK_B_DEVICES}  rack-c=${FAKE_RACK_C_DEVICES}"
stamped "  load:     ${K6_TARGET_RPS} RPS for ${K6_DURATION}"

# ── Phase 1/4: building images (if needed) ───────────────────────────────────
msh_emit_phase "building" 1 4
stamped "Building fake-adapter images"
docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" build 2>&1 | tail -3

# ── Phase 2/4: compose up ────────────────────────────────────────────────────
msh_emit_phase "starting" 2 4
stamped "Bringing containers up (rack-a, rack-b, rack-c, manager)"
docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d

# ── Phase 3/4: manager health wait ───────────────────────────────────────────
msh_emit_phase "starting" 3 4
stamped "Waiting for manager to be healthy at ${MANAGER_URL}/live"
deadline=$(( $(date +%s) + 120 ))
until curl -sf "${MANAGER_URL}/live" >/dev/null; do
    if (( $(date +%s) >= deadline )); then
        echo "!! Manager did not become healthy within 120s" >&2
        docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" logs --tail=100 >&2
        exit 1
    fi
    sleep 0.3
done
stamped "Manager is healthy"

# ── Phase 4/4: sanity checks (providers + baseline session) ──────────────────
msh_emit_phase "verifying" 4 4
stamped "Verifying providers and baseline allocation"
devices_json="$(curl -sf "${MANAGER_URL}/api/v1/devices" || echo '{"providers":[],"totalAvailable":0,"totalBusy":0}')"
expected_total=$((FAKE_RACK_A_DEVICES + FAKE_RACK_B_DEVICES + FAKE_RACK_C_DEVICES))
reported_summary="$(echo "${devices_json}" | python3 -c '
import json, sys
data = json.load(sys.stdin)
providers = data.get("providers", [])
total = sum(p.get("pool", {}).get("total", 0) for p in providers)
print(f"{total} {len(providers)}")
' 2>/dev/null || echo "0 0")"
reported_total="${reported_summary%% *}"
reported_count="${reported_summary##* }"
if [[ "${reported_total}" != "${expected_total}" ]]; then
    echo "!! Expected total=${expected_total} devices across providers, manager reports ${reported_total}" >&2
    echo "!! Full devices response:" >&2
    echo "${devices_json}" >&2
    echo "!! Manager logs (last 50 lines):" >&2
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" logs --tail=50 manager >&2 2>&1 || true
    exit 1
fi
stamped "Manager sees ${reported_total} devices across ${reported_count} providers"

# Baseline end-to-end: one session create + release. Proves the happy path
# works before we ramp up load.
session_json="$(curl -sf -X POST -H 'Content-Type: application/json' \
    -d '{"maxDevices":1,"api":"34","ttlSeconds":30}' \
    "${MANAGER_URL}/api/v1/sessions")"
session_id="$(echo "${session_json}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
if [[ -z "${session_id}" ]]; then
    echo "!! Failed to create baseline session: ${session_json}" >&2
    exit 1
fi
curl -sf -X DELETE "${MANAGER_URL}/api/v1/sessions/${session_id}" >/dev/null
stamped "Baseline session ${session_id} created+released"

# ── Churn: default driver is the built-in python_churn; opt into k6 ─────────
# explicitly via MSH_USE_K6=1 (set by `run_tests.sh --k6`). Having a single
# deterministic default removes "works on my machine because I happen to have
# k6 installed" surprises between dev and CI.
msh_emit_phase "testing"
if [[ "${MSH_USE_K6:-0}" == "1" ]]; then
    if ! command -v k6 >/dev/null 2>&1; then
        echo "!! --k6 was requested but k6 is not on PATH." >&2
        echo "   Install with one of:" >&2
        echo "     macOS:  brew install k6" >&2
        echo "     Linux:  https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
        echo "     binary: https://github.com/grafana/k6/releases" >&2
        echo "   …or drop the --k6 flag to use the built-in Python driver." >&2
        exit 1
    fi
    stamped "Driving load with k6 (${K6_TARGET_RPS} RPS for ${K6_DURATION})"
    k6 run \
        -e "MSH_URL=${MANAGER_URL}" \
        -e "TARGET_RPS=${K6_TARGET_RPS}" \
        -e "DURATION=${K6_DURATION}" \
        -e "API_LEVEL=34" \
        -e "DEVICES_PER_SESSION=1" \
        "${SCRIPT_DIR}/k6_session_churn.js"
else
    stamped "Driving load with built-in python_churn (${K6_TARGET_RPS} RPS for ${K6_DURATION})"
    python3 "${SCRIPT_DIR}/python_churn.py" \
        --url "${MANAGER_URL}" \
        --duration-seconds "${K6_DURATION%s}" \
        --target-rps "${K6_TARGET_RPS}" \
        --api-level 34
fi

stamped "Scale test completed"
