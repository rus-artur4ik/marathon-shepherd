#!/usr/bin/env bash
set -euo pipefail

# health-check.sh — Verify that all farm hosts are reachable and healthy.
# Reads resources/farm-config.yaml and checks each host.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG="${REPO_ROOT}/resources/farm-config.yaml"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASSED=0
WARNINGS=0
FAILED=0

ok()   { echo -e "  ${GREEN}✓${NC} $1"; ((PASSED++)); }
warn() { echo -e "  ${YELLOW}⚠${NC} $1"; ((WARNINGS++)); }
fail() { echo -e "  ${RED}✗${NC} $1"; ((FAILED++)); }

# ── Check local prerequisites ────────────────────────────────────────────────
echo "=== Local prerequisites ==="

for cmd in adb docker farm-cli-client marathon python3; do
    if command -v "$cmd" &>/dev/null; then
        ok "$cmd found ($(command -v "$cmd"))"
    else
        fail "$cmd not found in PATH"
    fi
done

if python3 -c "import yaml" 2>/dev/null; then
    ok "python3 pyyaml module available"
else
    fail "python3 pyyaml module missing (pip install pyyaml)"
fi

if [ -e /dev/kvm ]; then
    ok "/dev/kvm present"
else
    warn "/dev/kvm not found (emulators will not work on this machine)"
fi

# ── Parse farm-config.yaml ───────────────────────────────────────────────────
echo ""
echo "=== Farm hosts ==="

if [ ! -f "$CONFIG" ]; then
    fail "farm-config.yaml not found at ${CONFIG}"
    echo ""
    echo "Results: ${PASSED} passed, ${WARNINGS} warnings, ${FAILED} failed"
    exit 1
fi

# Use python3+pyyaml to parse YAML reliably
HOST_DATA=$(python3 -c "
import yaml, json, sys
with open('${CONFIG}') as f:
    data = yaml.safe_load(f)
for h in data.get('hosts', []):
    print(json.dumps(h))
")

while IFS= read -r host_json; do
    name=$(echo "$host_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['name'])")
    adb_host=$(echo "$host_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['adb_host'])")
    adb_port=$(echo "$host_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['adb_port'])")
    has_farm=$(echo "$host_json" | python3 -c "import json,sys; d=json.load(sys.stdin); print('true' if d.get('farm_server') else 'false')")
    has_physical=$(echo "$host_json" | python3 -c "import json,sys; print(json.load(sys.stdin).get('physical_devices', False))")

    echo ""
    echo "--- ${name} (${adb_host}:${adb_port}) ---"

    # Check ADB connectivity
    if adb connect "${adb_host}:${adb_port}" 2>/dev/null | grep -q "connected"; then
        ok "ADB reachable"
    else
        fail "ADB unreachable at ${adb_host}:${adb_port}"
    fi

    # Check farm-server if present
    if [ "$has_farm" = "true" ]; then
        farm_url=$(echo "$host_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['farm_server']['url'])")
        if curl -sf "${farm_url}/health" >/dev/null 2>&1; then
            ok "farm-server responding at ${farm_url}"
        else
            fail "farm-server NOT responding at ${farm_url}"
        fi
    fi

    # Check physical devices if expected
    if [ "$has_physical" = "True" ] || [ "$has_physical" = "true" ]; then
        device_count=$(adb -H "$adb_host" -P "$adb_port" devices 2>/dev/null \
                       | grep -c 'device$' \
                       | grep -cv 'emulator-' 2>/dev/null || echo 0)
        if [ "$device_count" -gt 0 ]; then
            ok "${device_count} physical device(s) detected"
        else
            warn "Host claims physical_devices=true but none detected"
        fi
    fi

done <<< "$HOST_DATA"

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "Results: ${PASSED} passed, ${WARNINGS} warnings, ${FAILED} failed"
echo "============================================"

if [ "$FAILED" -gt 0 ]; then
    exit 1
fi
