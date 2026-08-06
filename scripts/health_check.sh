#!/usr/bin/env bash
set -euo pipefail

# health_check.sh — Verify manager and adapter health for the current topology.
# Reads an MSH provider config file and queries the manager plus every configured adapter.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MANAGER_URL="${MSH_URL:-http://localhost:6037}"
CONFIG="${MSH_CONFIG:-${REPO_ROOT}/deploy/msh.yaml.example}"

usage() {
    cat <<EOF
Usage: $(basename "$0") [--manager URL] [--config PATH]

Options:
  --manager URL  Manager base URL. Default: ${MANAGER_URL}
  --config PATH  Provider config YAML. Default: ${CONFIG}
  --help         Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --manager)
            MANAGER_URL="$2"
            shift 2
            ;;
        --config)
            CONFIG="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

PASSED=0
WARNINGS=0
FAILED=0

# Plain arithmetic assignment, not `((VAR++))`: under `set -e` the post-increment
# form returns the pre-increment value, so the very first successful check (0 -> 1)
# exited with status 1 and killed the script.
ok()   { echo "  [OK]   $1"; PASSED=$((PASSED + 1)); }
warn() { echo "  [WARN] $1"; WARNINGS=$((WARNINGS + 1)); }
fail() { echo "  [FAIL] $1"; FAILED=$((FAILED + 1)); }

# ── Check local prerequisites ────────────────────────────────────────────────
echo "=== Local prerequisites ==="

for cmd in curl python3; do
    if command -v "$cmd" &>/dev/null; then
        ok "$cmd found ($(command -v "$cmd"))"
    else
        fail "$cmd not found in PATH"
    fi
done

if python3 -c "import yaml" 2>/dev/null; then
    ok "python3 pyyaml module available"
else
    fail "python3 pyyaml module missing — install it with: python3 -m pip install pyyaml"
fi

echo ""
echo "=== Manager ==="

manager_live_response=$(curl -sS -w $'\n%{http_code}' "${MANAGER_URL}/live" || true)
manager_live_body="${manager_live_response%$'\n'*}"
manager_live_code="${manager_live_response##*$'\n'}"

if [[ "$manager_live_code" =~ ^2 ]]; then
    ok "Manager liveness responded at ${MANAGER_URL}"
else
    fail "Manager liveness failed at ${MANAGER_URL} (HTTP ${manager_live_code})"
fi

if [[ -n "$manager_live_body" ]]; then
    echo "  ${manager_live_body}"
fi

manager_health_response=$(curl -sS -w $'\n%{http_code}' "${MANAGER_URL}/health" || true)
manager_health_body="${manager_health_response%$'\n'*}"
manager_health_code="${manager_health_response##*$'\n'}"

if [[ "$manager_health_code" =~ ^2 ]]; then
    ok "Manager readiness reported healthy providers"
elif [[ "$manager_health_code" == "503" ]]; then
    warn "Manager is alive but readiness is not satisfied yet (HTTP 503)"
else
    fail "Manager readiness check failed at ${MANAGER_URL} (HTTP ${manager_health_code})"
fi

if [[ -n "$manager_health_body" ]]; then
    echo "  ${manager_health_body}"
fi

if [ ! -f "$CONFIG" ]; then
    fail "Provider config not found at ${CONFIG}"
    echo ""
    echo "Results: ${PASSED} passed, ${WARNINGS} warnings, ${FAILED} failed"
    exit 1
fi

devices_response=$(curl -sS -w $'\n%{http_code}' "${MANAGER_URL}/api/v1/devices" || true)
devices_body="${devices_response%$'\n'*}"
devices_code="${devices_response##*$'\n'}"

if [[ "$devices_code" =~ ^2 ]]; then
    ok "Manager device inventory endpoint responded"
    echo "  ${devices_body}"
else
    warn "Manager device inventory endpoint returned HTTP ${devices_code}"
fi

echo ""
echo "=== Providers ==="

provider_data=$(python3 - "$CONFIG" <<'PY'
import yaml, json, sys
config_path = sys.argv[1]
with open(config_path) as handle:
    data = yaml.safe_load(handle) or {}
for provider in data.get('providers', []):
    print(json.dumps(provider))
PY
)

if [[ -z "${provider_data}" ]]; then
    warn "No providers configured in ${CONFIG}"
fi

while IFS= read -r provider_json; do
    [[ -z "$provider_json" ]] && continue
    name=$(echo "$provider_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['name'])")
    url=$(echo "$provider_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['url'])")
    secret=$(echo "$provider_json" | python3 -c "import json,sys; print(json.load(sys.stdin).get('secret', ''))")
    echo ""
    echo "--- ${name} (${url}) ---"

    provider_health=$(curl -sS -w $'\n%{http_code}' "${url}/health" || true)
    provider_health_body="${provider_health%$'\n'*}"
    provider_health_code="${provider_health##*$'\n'}"

    if [[ "$provider_health_code" =~ ^2 ]]; then
        ok "Adapter health endpoint responded"
        echo "  ${provider_health_body}"
    else
        fail "Adapter health endpoint failed (HTTP ${provider_health_code})"
    fi

    if [[ -n "$secret" ]]; then
        provider_status=$(curl -sS -H "Authorization: Bearer ${secret}" -w $'\n%{http_code}' "${url}/status" || true)
    else
        provider_status=$(curl -sS -w $'\n%{http_code}' "${url}/status" || true)
    fi
    provider_status_body="${provider_status%$'\n'*}"
    provider_status_code="${provider_status##*$'\n'}"

    if [[ "$provider_status_code" =~ ^2 ]]; then
        ok "Adapter status endpoint responded"
        summary=$(echo "$provider_status_body" | python3 -c "
import json,sys
status = json.load(sys.stdin)
pool = status.get('pool') or {}
if not pool and all(k in status for k in ('available', 'busy', 'total')):
    pool = {
        'available': status.get('available', 0),
        'busy': status.get('busy', 0),
        'total': status.get('total', 0)
    }
available = pool.get('available', 0)
busy = pool.get('busy', 0)
total = pool.get('total', 0)

access = status.get('access') or {}
connections = access.get('connections') or []
preferred_id = access.get('preferredConnectionId')
preferred = None
if preferred_id:
    preferred = next((connection for connection in connections if connection.get('id') == preferred_id), None)
if preferred is None and connections:
    preferred = connections[0]

if preferred:
    auth = (preferred.get('auth') or {}).get('type', 'unknown')
    access_summary = (
        f\"{preferred.get('protocol', 'unknown')}/{preferred.get('transport', 'unknown')} \"
        f\"{preferred.get('host', 'unknown')}:{preferred.get('port', '?')} \"
        f\"[{preferred.get('exposure', 'unknown')}, auth={auth}]\"
    )
else:
    access_summary = f\"adb={status.get('adbHost', 'unknown')}:{status.get('adbPort', '?')}\"

print(
    f\"available={available}, busy={busy}, total={total}, {access_summary}\"
)
")
        echo "  ${summary}"
    else
        fail "Adapter status endpoint failed (HTTP ${provider_status_code})"
    fi
done <<< "$provider_data"

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "Results: ${PASSED} passed, ${WARNINGS} warnings, ${FAILED} failed"
echo "============================================"

if [ "$FAILED" -gt 0 ]; then
    exit 1
fi
