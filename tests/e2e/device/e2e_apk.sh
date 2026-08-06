#!/usr/bin/env bash
# Full end-to-end test: start Shepherd (manager + shepherd-adb using built
# binaries), allocate a session, install the pinned APKs *through the
# session-scoped ADB proxy*, run instrumentation, assert all tests pass,
# then release the session.
#
# Green = the complete Shepherd flow works: allocation → ADB proxy → device →
#         Android instrumentation tests → session cleanup.
#
# Prerequisites:
#   ./gradlew :manager:service:installDist :adapter:shepherd-adb:installDist
#   An ADB device connected and listed by `adb devices`
#   APKs present: tests/public_ui/apks/architecture-samples-*/
#
# Optional env vars:
#   ADB_SERIAL          Force a specific device serial
#   MSH_E2E_TTL         Session TTL in seconds (default: 300)
#   MSH_E2E_APK_PUSH_TIMEOUT_SECONDS     Timeout for proxy APK push (default: 120)
#   MSH_E2E_APK_INSTALL_TIMEOUT_SECONDS  Timeout for proxy pm install (default: 180)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
source "${REPO_ROOT}/tests/helpers/common.sh"
source "${REPO_ROOT}/tests/helpers/stage_runtime.sh"

APK_DIR="${REPO_ROOT}/tests/public_ui/apks/architecture-samples-ee66e1526b84c026615df032c705842b7d2a521f-androidx-test-3.7.0"
APP_APK="${APK_DIR}/app-debug.apk"
TEST_APK="${APK_DIR}/app-debug-androidTest.apk"
TEST_RUNNER="com.example.android.architecture.blueprints.main.test/com.example.android.architecture.blueprints.todoapp.CustomTestRunner"
APP_PACKAGE="com.example.android.architecture.blueprints.main"
TEST_PACKAGE="com.example.android.architecture.blueprints.main.test"

MANAGER_BIN="${REPO_ROOT}/manager/service/build/install/manager/bin/manager"
ADB_ADAPTER_BIN="${REPO_ROOT}/adapter/shepherd-adb/build/install/shepherd-adb/bin/shepherd-adb"
SESSION_TTL="${MSH_E2E_TTL:-300}"
APK_PUSH_TIMEOUT_SECONDS="${MSH_E2E_APK_PUSH_TIMEOUT_SECONDS:-120}"
APK_INSTALL_TIMEOUT_SECONDS="${MSH_E2E_APK_INSTALL_TIMEOUT_SECONDS:-180}"
TOTAL_TEST_SCENARIOS=9
stage_runtime_init "E2E APK" "${TOTAL_TEST_SCENARIOS}"

TMP_ROOT=""
ADB_ADAPTER_PID=""
MANAGER_PID=""
SESSION_ID=""
MANAGER_URL=""
PROXY_DEVICE_SERIAL=""
REMOTE_APP_APK_PATH="/data/local/tmp/msh-e2e-app.apk"
REMOTE_TEST_APK_PATH="/data/local/tmp/msh-e2e-test.apk"
INSTALL_FAILURE_STEP=""
INSTALL_FAILURE_REASON=""

cleanup() {
    # Keep the dashboard label truthful: while we kill subprocesses and rm
    # temp dirs, the phase must say "cleanup" rather than a stale "testing".
    stage_emit_phase "cleanup" 2>/dev/null || true
    if [[ -n "${SESSION_ID}" && -n "${MANAGER_URL}" ]]; then
        http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}" || true
    fi
    if [[ -n "${MANAGER_PID}" ]]; then
        kill "${MANAGER_PID}" >/dev/null 2>&1 || true
        wait "${MANAGER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${ADB_ADAPTER_PID}" ]]; then
        kill "${ADB_ADAPTER_PID}" >/dev/null 2>&1 || true
        wait "${ADB_ADAPTER_PID}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${TMP_ROOT}" && -d "${TMP_ROOT}" ]]; then
        rm -rf "${TMP_ROOT}"
    fi
}
trap cleanup EXIT

find_free_port() {
    python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

run_command_with_timeout() {
    local timeout_seconds="$1"
    local log_file="$2"
    shift 2
    python3 - "$timeout_seconds" "$log_file" "$@" <<'PY'
import subprocess
import sys

timeout_seconds = int(sys.argv[1])
log_file = sys.argv[2]
command = sys.argv[3:]

with open(log_file, "w", encoding="utf-8") as log_handle:
    try:
        completed = subprocess.run(
            command,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            timeout=timeout_seconds,
            check=False,
        )
        raise SystemExit(completed.returncode)
    except subprocess.TimeoutExpired:
        print(f"Command timed out after {timeout_seconds}s", file=log_handle)
        raise SystemExit(124)
PY
}

stream_file_with_timeout() {
    local timeout_seconds="$1"
    local log_file="$2"
    local input_file="$3"
    shift 3
    python3 - "$timeout_seconds" "$log_file" "$input_file" "$@" <<'PY'
import subprocess
import sys

timeout_seconds = int(sys.argv[1])
log_file = sys.argv[2]
input_file = sys.argv[3]
command = sys.argv[4:]

with open(log_file, "w", encoding="utf-8") as log_handle:
    with open(input_file, "rb") as input_handle:
        try:
            completed = subprocess.run(
                command,
                stdin=input_handle,
                stdout=log_handle,
                stderr=subprocess.STDOUT,
                timeout=timeout_seconds,
                check=False,
            )
            raise SystemExit(completed.returncode)
        except subprocess.TimeoutExpired:
            print(f"Command timed out after {timeout_seconds}s", file=log_handle)
            raise SystemExit(124)
PY
}

install_apk_via_proxy() {
    local local_apk_path="$1"
    local remote_apk_path="$2"
    shift 2
    local install_args=("$@")
    local status=0
    INSTALL_FAILURE_STEP=""
    INSTALL_FAILURE_REASON=""

    stream_file_with_timeout \
        "${APK_PUSH_TIMEOUT_SECONDS}" \
        "${TMP_ROOT}/apk-transfer.log" \
        "${local_apk_path}" \
        adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" -s "${PROXY_DEVICE_SERIAL}" \
        exec-in sh -c "cat > '${remote_apk_path}'"
    status="$?"
    if [[ "${status}" != "0" ]]; then
        INSTALL_FAILURE_STEP="stream upload"
        if [[ "${status}" == "124" ]]; then
            INSTALL_FAILURE_REASON="timeout after ${APK_PUSH_TIMEOUT_SECONDS}s"
        else
            INSTALL_FAILURE_REASON="exit code ${status}"
        fi
        return 1
    fi

    run_command_with_timeout \
        "${APK_INSTALL_TIMEOUT_SECONDS}" \
        "${TMP_ROOT}/apk-install.log" \
        adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" -s "${PROXY_DEVICE_SERIAL}" \
        shell pm install "${install_args[@]}" "${remote_apk_path}"
    status="$?"
    if [[ "${status}" != "0" ]]; then
        INSTALL_FAILURE_STEP="pm install"
        if [[ "${status}" == "124" ]]; then
            INSTALL_FAILURE_REASON="timeout after ${APK_INSTALL_TIMEOUT_SECONDS}s"
        else
            INSTALL_FAILURE_REASON="exit code ${status}"
        fi
        return 1
    fi

    run_command_with_timeout \
        "30" \
        "${TMP_ROOT}/apk-cleanup.log" \
        adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" -s "${PROXY_DEVICE_SERIAL}" \
        shell rm -f "${remote_apk_path}" >/dev/null 2>&1 || true
}

# ── Prerequisites ─────────────────────────────────────────────────────────────

stage_begin_phase "preparing" "Checking prerequisites"
require_command python3
require_command curl
require_command adb

if [[ ! -x "${MANAGER_BIN}" ]]; then
    fail "Manager binary not found. Run: ./gradlew :manager:service:installDist"
fi
if [[ ! -x "${ADB_ADAPTER_BIN}" ]]; then
    fail "shepherd-adb binary not found. Run: ./gradlew :adapter:shepherd-adb:installDist"
fi
if [[ ! -f "${APP_APK}" || ! -f "${TEST_APK}" ]]; then
    # The sample APKs are ~58 MB of regenerable third-party build output and are
    # deliberately not committed. Build them once; they are cached on disk.
    fail "Sample APKs not found under ${APK_DIR}. Build them first: ./scripts/build_test_apk.sh"
fi

# ── Device detection ──────────────────────────────────────────────────────────

stage_begin_phase "detecting" "Detecting connected ADB device"
CONNECTED_SERIALS="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [[ -z "${CONNECTED_SERIALS}" ]]; then
    fail "No ADB device connected. Connect a physical device or start an emulator before running this test."
fi

if [[ -n "${ADB_SERIAL:-}" ]]; then
    if ! echo "${CONNECTED_SERIALS}" | grep -qF "${ADB_SERIAL}"; then
        fail "Requested device ${ADB_SERIAL} is not connected. Connected: $(echo "${CONNECTED_SERIALS}" | tr '\n' ' ')"
    fi
    DEVICE_SERIAL="${ADB_SERIAL}"
else
    DEVICE_SERIAL="$(echo "${CONNECTED_SERIALS}" | head -1)"
fi

DEVICE_API="$(adb -s "${DEVICE_SERIAL}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
if [[ -z "${DEVICE_API}" ]]; then
    DEVICE_API="34"
fi
log_success "Device: ${DEVICE_SERIAL} (API ${DEVICE_API})"

# ── Shepherd startup ──────────────────────────────────────────────────────────

TMP_ROOT="$(mktemp -d)"
STATE_DIR="${TMP_ROOT}/state"
mkdir -p "${STATE_DIR}"

MANAGER_PORT="$(find_free_port)"
ADB_ADAPTER_PORT="$(find_free_port)"
PROXY_BASE_PORT="$(find_free_port)"
MANAGER_URL="http://127.0.0.1:${MANAGER_PORT}"
ADB_ADAPTER_URL="http://127.0.0.1:${ADB_ADAPTER_PORT}"
ADAPTER_SECRET="e2e-secret"

printf '%s\n' \
    "providers:" \
    "  - name: \"e2e-adb\"" \
    "    url: \"${ADB_ADAPTER_URL}\"" \
    "    secret: \"${ADAPTER_SECRET}\"" \
    > "${TMP_ROOT}/msh.yaml"

stage_begin_phase "starting" "Starting shepherd-adb (connecting to local ADB server on port 5037)" 1 2
ADAPTER_PORT="${ADB_ADAPTER_PORT}" \
ADAPTER_ADB_PORT="5037" \
ADB_PROXY_PORT_RANGE="${PROXY_BASE_PORT}-$((PROXY_BASE_PORT + 9))" \
ADAPTER_SECRET="${ADAPTER_SECRET}" \
ADB_LEASES_PATH="${TMP_ROOT}/adb-leases.json" \
"${ADB_ADAPTER_BIN}" > "${TMP_ROOT}/shepherd-adb.log" 2>&1 &
ADB_ADAPTER_PID="$!"

if ! wait_for_http_json "${ADB_ADAPTER_URL}/health" 60; then
    cat "${TMP_ROOT}/shepherd-adb.log" || true
    fail "shepherd-adb did not become healthy within 60 seconds"
fi
log_success "shepherd-adb healthy at ${ADB_ADAPTER_URL}"

stage_begin_phase "starting" "Starting manager" 2 2
MSH_PORT="${MANAGER_PORT}" \
MSH_CONFIG="${TMP_ROOT}/msh.yaml" \
MSH_DATA_DIR="${STATE_DIR}" \
"${MANAGER_BIN}" > "${TMP_ROOT}/manager.log" 2>&1 &
MANAGER_PID="$!"

if ! wait_for_http_json "${MANAGER_URL}/health" 60; then
    cat "${TMP_ROOT}/manager.log" || true
    fail "Manager did not become healthy within 60 seconds"
fi
log_success "Manager healthy at ${MANAGER_URL}"

# ── Session allocation ────────────────────────────────────────────────────────

stage_begin_test "Allocating session via Shepherd (deviceType=physical, api=${DEVICE_API})"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" \
    "{\"maxDevices\":1,\"api\":\"${DEVICE_API}\",\"ttlSeconds\":${SESSION_TTL},\"deviceType\":\"physical\"}"
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Session must be READY"
assert_json_expr 'len(payload.get("adbServers", [])) >= 1' "Session must include at least 1 adbServer"
SESSION_ID="$(extract_json_value 'payload.get("id")')"
PROXY_HOST="$(extract_json_value 'payload.get("adbServers", [{}])[0].get("host", "127.0.0.1")')"
PROXY_PORT="$(extract_json_value 'payload.get("adbServers", [{}])[0].get("port")')"
log_success "Session ${SESSION_ID}: proxy = ${PROXY_HOST}:${PROXY_PORT}"

# ── ADB proxy validation ──────────────────────────────────────────────────────

stage_begin_test "Verifying lease isolation: proxy exposes exactly 1 device"
PROXY_DEVICES="$(adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" devices 2>/dev/null \
    | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [[ -z "${PROXY_DEVICES}" ]]; then
    cat "${TMP_ROOT}/shepherd-adb.log" || true
    fail "No devices visible through Shepherd ADB proxy at ${PROXY_HOST}:${PROXY_PORT}"
fi
PROXY_DEVICE_COUNT="$(echo "${PROXY_DEVICES}" | wc -l | tr -d ' ')"
if [[ "${PROXY_DEVICE_COUNT}" != "1" ]]; then
    fail "Expected exactly 1 device through proxy, got ${PROXY_DEVICE_COUNT}: $(echo "${PROXY_DEVICES}" | tr '\n' ' ')"
fi
PROXY_DEVICE_SERIAL="$(echo "${PROXY_DEVICES}" | head -1)"
log_success "Shepherd proxy exposes exactly 1 device — lease isolation confirmed"

# ── APK installation ──────────────────────────────────────────────────────────

stage_begin_test "Installing app APK through Shepherd proxy"
if ! install_apk_via_proxy "${APP_APK}" "${REMOTE_APP_APK_PATH}" -r; then
    cat "${TMP_ROOT}/apk-transfer.log" 2>/dev/null || true
    cat "${TMP_ROOT}/apk-install.log" 2>/dev/null || true
    emit_failure_cause \
        "App APK installation failed" \
        "${INSTALL_FAILURE_STEP} completes successfully (${INSTALL_FAILURE_REASON})" \
        "$(cat "${TMP_ROOT}/apk-transfer.log" 2>/dev/null || true) $(cat "${TMP_ROOT}/apk-install.log" 2>/dev/null || true)"
    fail "App APK installation failed"
fi
log_success "App APK installed"

stage_begin_test "Installing test APK through Shepherd proxy"
if ! install_apk_via_proxy "${TEST_APK}" "${REMOTE_TEST_APK_PATH}" -r -t; then
    cat "${TMP_ROOT}/apk-transfer.log" 2>/dev/null || true
    cat "${TMP_ROOT}/apk-install.log" 2>/dev/null || true
    emit_failure_cause \
        "Test APK installation failed" \
        "${INSTALL_FAILURE_STEP} completes successfully (${INSTALL_FAILURE_REASON})" \
        "$(cat "${TMP_ROOT}/apk-transfer.log" 2>/dev/null || true) $(cat "${TMP_ROOT}/apk-install.log" 2>/dev/null || true)"
    fail "Test APK installation failed"
fi
log_success "Test APK installed"

# ── Instrumentation ───────────────────────────────────────────────────────────

stage_begin_test "Running instrumentation tests through Shepherd proxy"
# Live test progress requires two parallel data sources:
#
#  1. `am instrument -w` THROUGH THE PROXY — captures the human-readable
#     `OK (N tests)` / `FAILURES!!!` summary that the assertions below depend
#     on. On API 36 with the current Shepherd smart-socket proxy this stream
#     can come back empty (adb-proxy closes the upstream before am flushes);
#     we still run it because it's the authoritative success signal.
#
#  2. `adb logcat -s TestRunner:I` DIRECT TO THE DEVICE — AndroidJUnitRunner
#     emits `run started: N tests` when the suite begins and `started: …` for
#     every test. Parsing these over a direct adb connection (bypassing the
#     proxy) gives reliable live progress even when path #1 is silent, without
#     affecting the semantics of what we're actually testing.
INSTRUMENT_LOG="${TMP_ROOT}/instrumentation.log"
INSTRUMENT_AM_LOG="${TMP_ROOT}/instrumentation-am.log"
# Clear logcat buffer so we only see runner events from this invocation.
adb -s "${DEVICE_SERIAL}" logcat -c >/dev/null 2>&1 || true

# Background progress observer: reads TestRunner lines from direct logcat,
# emits MSH_TEST_PROGRESS markers into the stage log as tests start.
# We use a plain pipeline (no subshell) so `$!` points to the python parser —
# killing it closes the adb pipe via SIGPIPE. `python3 -u` keeps the stream
# unbuffered end-to-end; the file is opened with `buffering=1` so each line
# reaches disk as soon as it's parsed.
adb -s "${DEVICE_SERIAL}" logcat -v raw -s TestRunner:I '*:S' 2>/dev/null \
    | python3 -u -c '
import re, sys, time
log_path = sys.argv[1]
total_re = re.compile(r"run started:\s*(\d+)\s*tests?", re.IGNORECASE)
# AndroidJUnitRunner format: `started: methodName(fully.qualified.ClassName)`
# Extract method + short class name so the log can show WHICH test is running,
# not just "30/38". The short name also rides along in MSH_TEST_PROGRESS as a
# trailing token so the runner can preserve it in `--scan` reports.
start_re = re.compile(r"^started:\s+(\S+?)\(([^)]+)\)\s*$")
total = 0
done = 0
phase_emitted = False
with open(log_path, "w", buffering=1, encoding="utf-8", errors="replace") as log_handle:
    for raw in sys.stdin:
        line = raw.rstrip("\n")
        stripped = line.strip()
        log_handle.write(line + "\n")
        if not stripped:
            continue
        m = total_re.search(stripped)
        if m:
            total = int(m.group(1))
            timestamp = time.strftime("%H:%M:%S")
            banner = f"[{timestamp}] Starting instrumentation run — {total} tests"
            print(banner, flush=True)
            log_handle.write(banner + "\n")
            continue
        sm = start_re.match(stripped)
        if sm and total > 0:
            done += 1
            method = sm.group(1)
            fq_class = sm.group(2)
            short_class = fq_class.rsplit(".", 1)[-1]
            short_name = f"{short_class}.{method}"
            if not phase_emitted:
                print("MSH_PHASE: testing", flush=True)
                log_handle.write("MSH_PHASE: testing\n")
                phase_emitted = True
            # Two-line output per test: a human-readable progress line (picked
            # up by the "Output" tail in the dashboard) and the machine marker
            # that the runner parses for the "testing [N/M]" counter. The
            # marker still carries the name so `--scan` timelines are useful.
            timestamp = time.strftime("%H:%M:%S")
            human_line = f"[{timestamp}] ▸ {done:>3}/{total}  {short_name}"
            marker = f"MSH_TEST_PROGRESS: {done}/{total} {short_name}"
            print(human_line, flush=True)
            print(marker, flush=True)
            log_handle.write(human_line + "\n")
            log_handle.write(marker + "\n")
' "${INSTRUMENT_LOG}" 2>/dev/null &
LOGCAT_PID=$!

# `am instrument -w` (no `-r`) produces the human-readable `OK (N tests)` /
# `FAILURES!!!` summary used by the assertions below. Capture to a separate
# file so the logcat observer never races with the am-output writer.
set +e
adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" \
    -s "${PROXY_DEVICE_SERIAL}" \
    shell am instrument -w "${TEST_RUNNER}" > "${INSTRUMENT_AM_LOG}" 2>&1
INSTRUMENT_EXIT=$?
set -e

# Instrumentation is done — give logcat 1s to drain any tail events, then
# stop the observer. Killing python closes the pipe; adb logcat terminates on
# SIGPIPE. `wait` swallows the exit code so `set -e` doesn't trip.
sleep 1
if kill -0 "${LOGCAT_PID}" 2>/dev/null; then
    kill "${LOGCAT_PID}" >/dev/null 2>&1 || true
    # Best-effort: also kill any orphaned adb logcat (sibling in the pipe).
    pkill -f "logcat -v raw -s TestRunner:I" >/dev/null 2>&1 || true
fi
wait "${LOGCAT_PID}" >/dev/null 2>&1 || true

# Concatenate the observer output (logcat + MSH_ markers) and the am output.
{
    cat "${INSTRUMENT_LOG}" 2>/dev/null || true
    cat "${INSTRUMENT_AM_LOG}" 2>/dev/null || true
} > "${INSTRUMENT_LOG}.merged"
mv -f "${INSTRUMENT_LOG}.merged" "${INSTRUMENT_LOG}"
INSTRUMENT_OUTPUT="$(cat "${INSTRUMENT_LOG}" 2>/dev/null || true)"

# If the proxy swallowed the `am instrument` stream, INSTRUMENT_OUTPUT is empty.
# Without this diagnostic we end up with a cryptic "success marker not found"
# + no actual output in MSH_FAILURE_ACTUAL. Dump an explicit breadcrumb so
# future runs show what actually came back.
if [[ -z "${INSTRUMENT_OUTPUT}" ]]; then
    diagnostic="adb-proxy returned empty stdout"
    diagnostic+=$'\n'"  proxy:   ${PROXY_HOST}:${PROXY_PORT}"
    diagnostic+=$'\n'"  device:  ${PROXY_DEVICE_SERIAL}"
    diagnostic+=$'\n'"  runner:  ${TEST_RUNNER}"
    diagnostic+=$'\n'"  adb-exit:${INSTRUMENT_EXIT}"
    diagnostic+=$'\n'"Hint: run 'adb -s ${DEVICE_SERIAL} shell am instrument -w ${TEST_RUNNER}' directly"
    diagnostic+=$'\n'"to confirm whether the issue is in the Shepherd proxy or the test APKs themselves."
    emit_failure_cause "Instrumentation output was empty" \
        "Non-empty adb shell output" \
        "${diagnostic}"
    fail "Shepherd proxy returned empty instrumentation output — see MSH_FAILURE_ACTUAL"
fi

if echo "${INSTRUMENT_OUTPUT}" | grep -qE "FAILURES!|INSTRUMENTATION_ABORTED|Error in"; then
    emit_failure_cause "Instrumentation tests reported failures" "OK (N tests)" "${INSTRUMENT_OUTPUT}"
    fail "Instrumentation tests reported failures"
fi
if ! echo "${INSTRUMENT_OUTPUT}" | grep -qE "^OK \([0-9]+ tests?\)|Tests run:"; then
    emit_failure_cause \
        "Instrumentation success marker was not found" \
        "Output contains 'OK (N tests)' or 'Tests run:'" \
        "${INSTRUMENT_OUTPUT}"
    fail "Could not confirm test success from instrumentation output (expected 'OK (N tests)' or 'Tests run:')"
fi
log_success "Instrumentation tests passed"

# ── Session release ───────────────────────────────────────────────────────────

stage_begin_test "Releasing session via Shepherd"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Session must be released"
SESSION_ID=""
log_success "Session released"

stage_begin_test "Re-allocating a second session after instrumentation cleanup"
http_json "POST" "${MANAGER_URL}/api/v1/sessions" \
    "{\"maxDevices\":1,\"api\":\"${DEVICE_API}\",\"ttlSeconds\":${SESSION_TTL},\"deviceType\":\"physical\"}"
assert_status "201"
assert_json_expr 'payload.get("status") == "READY"' "Second session must also be READY"
assert_json_expr 'len(payload.get("adbServers", [])) >= 1' "Second session must include at least 1 adbServer"
SESSION_ID="$(extract_json_value 'payload.get("id")')"
PROXY_HOST="$(extract_json_value 'payload.get("adbServers", [{}])[0].get("host", "127.0.0.1")')"
PROXY_PORT="$(extract_json_value 'payload.get("adbServers", [{}])[0].get("port")')"
PROXY_DEVICES="$(adb -H "${PROXY_HOST}" -P "${PROXY_PORT}" devices 2>/dev/null \
    | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [[ -z "${PROXY_DEVICES}" ]]; then
    fail "Second Shepherd session did not expose any devices through the ADB proxy"
fi
if [[ "$(echo "${PROXY_DEVICES}" | wc -l | tr -d ' ')" != "1" ]]; then
    fail "Expected exactly 1 device through the second proxy, got: $(echo "${PROXY_DEVICES}" | tr '\n' ' ')"
fi
PROXY_DEVICE_SERIAL="$(echo "${PROXY_DEVICES}" | head -1)"
log_success "Second allocation succeeded after instrumentation cleanup"

stage_begin_test "Releasing the second session"
http_json "DELETE" "${MANAGER_URL}/api/v1/sessions/${SESSION_ID}"
assert_status "200"
assert_json_expr 'payload.get("status") == "released"' "Second session must be released"
SESSION_ID=""
log_success "Second session released"

stage_begin_test "Verifying no active sessions remain"
http_json "GET" "${MANAGER_URL}/api/v1/sessions"
assert_status "200"
assert_json_expr 'all(s.get("status") in ("RELEASED", "EXPIRED", "FAILED") for s in payload)' \
    "Expected only terminal session statuses"
log_success "No active sessions remain"

# ── APK cleanup ───────────────────────────────────────────────────────────────

stage_begin_phase "cleanup" "Uninstalling APKs"
adb -s "${DEVICE_SERIAL}" uninstall "${APP_PACKAGE}" >/dev/null 2>&1 || true
adb -s "${DEVICE_SERIAL}" uninstall "${TEST_PACKAGE}" >/dev/null 2>&1 || true
log_success "APKs uninstalled"

log_step "E2E test completed"
log_success "Full Shepherd flow verified: allocation → ADB proxy → lease isolation → instrumentation → cleanup"
