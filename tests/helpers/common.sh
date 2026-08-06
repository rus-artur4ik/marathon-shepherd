#!/usr/bin/env bash
set -euo pipefail

LOG_COLOR_RESET=""
LOG_COLOR_STEP=""
LOG_COLOR_INFO=""
LOG_COLOR_WARN=""
LOG_COLOR_ERROR=""
LOG_MODE="${MSH_LOG_MODE:-plain}"
DYNAMIC_LOGS_AVAILABLE=false
DYNAMIC_LOGS_ENABLED=false
LOG_CONTEXT="Integration"
LOG_BOARD_LINES_PRINTED=0
LOG_BOARD_CURRENT_INDEX=-1
LOG_BOARD_SPINNER_INDEX=0
LOG_BOARD_LABELS=()
LOG_BOARD_STATUSES=()
LOG_BOARD_DETAILS=()
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
    LOG_COLOR_RESET=$'\033[0m'
    LOG_COLOR_STEP=$'\033[1;36m'
    LOG_COLOR_INFO=$'\033[0;32m'
    LOG_COLOR_WARN=$'\033[1;33m'
    LOG_COLOR_ERROR=$'\033[1;31m'
fi
if [[ "${LOG_MODE}" == "dynamic" && -t 1 ]]; then
    DYNAMIC_LOGS_AVAILABLE=true
fi

log_timestamp() {
    date '+%H:%M:%S'
}

set_log_context() {
    LOG_CONTEXT="$1"
}

enable_dynamic_logs() {
    if [[ "${DYNAMIC_LOGS_AVAILABLE}" == "true" ]]; then
        DYNAMIC_LOGS_ENABLED=true
        render_dynamic_log_board
    fi
}

clear_dynamic_log_board() {
    local index=0
    if (( LOG_BOARD_LINES_PRINTED == 0 )); then
        return
    fi
    printf '\033[%dA' "${LOG_BOARD_LINES_PRINTED}"
    for ((index = 0; index < LOG_BOARD_LINES_PRINTED; index++)); do
        printf '\r\033[2K'
        if (( index + 1 < LOG_BOARD_LINES_PRINTED )); then
            printf '\033[1B'
        fi
    done
    if (( LOG_BOARD_LINES_PRINTED > 1 )); then
        printf '\033[%dA' "$((LOG_BOARD_LINES_PRINTED - 1))"
    fi
}

dynamic_status_badge() {
    local status="$1"
    local spinner_frames='-\|/'
    local frame="${spinner_frames:${LOG_BOARD_SPINNER_INDEX}:1}"
    case "${status}" in
        running)
            printf "%s[%s]%s" "${LOG_COLOR_STEP}" "${frame}" "${LOG_COLOR_RESET}"
            ;;
        success)
            printf "%s[OK]%s" "${LOG_COLOR_INFO}" "${LOG_COLOR_RESET}"
            ;;
        skipped)
            printf "%s[SK]%s" "${LOG_COLOR_WARN}" "${LOG_COLOR_RESET}"
            ;;
        failed)
            printf "%s[ER]%s" "${LOG_COLOR_ERROR}" "${LOG_COLOR_RESET}"
            ;;
        *)
            printf "[..]"
            ;;
    esac
}

render_dynamic_log_board() {
    local index=0
    local detail=""
    local badge=""
    if [[ "${DYNAMIC_LOGS_ENABLED}" != "true" ]]; then
        return
    fi
    clear_dynamic_log_board
    printf "Marathon Shepherd | %s\n" "${LOG_CONTEXT}"
    printf "%s\n" "========================================================================"
    for ((index = 0; index < ${#LOG_BOARD_LABELS[@]}; index++)); do
        badge="$(dynamic_status_badge "${LOG_BOARD_STATUSES[index]}")"
        detail="${LOG_BOARD_DETAILS[index]}"
        if [[ -n "${detail}" ]]; then
            printf "%s %s :: %s\n" "${badge}" "${LOG_BOARD_LABELS[index]}" "${detail}"
        else
            printf "%s %s\n" "${badge}" "${LOG_BOARD_LABELS[index]}"
        fi
    done
    LOG_BOARD_SPINNER_INDEX=$(((LOG_BOARD_SPINNER_INDEX + 1) % 4))
    LOG_BOARD_LINES_PRINTED=$((2 + ${#LOG_BOARD_LABELS[@]}))
}

start_dynamic_stage() {
    local message="$1"
    if [[ "${DYNAMIC_LOGS_ENABLED}" != "true" ]]; then
        return
    fi
    if (( LOG_BOARD_CURRENT_INDEX >= 0 )) && [[ "${LOG_BOARD_STATUSES[LOG_BOARD_CURRENT_INDEX]}" == "running" ]]; then
        LOG_BOARD_STATUSES[LOG_BOARD_CURRENT_INDEX]="success"
        if [[ -z "${LOG_BOARD_DETAILS[LOG_BOARD_CURRENT_INDEX]}" ]]; then
            LOG_BOARD_DETAILS[LOG_BOARD_CURRENT_INDEX]="Completed"
        fi
    fi
    LOG_BOARD_LABELS[${#LOG_BOARD_LABELS[@]}]="${message}"
    LOG_BOARD_STATUSES[${#LOG_BOARD_STATUSES[@]}]="running"
    LOG_BOARD_DETAILS[${#LOG_BOARD_DETAILS[@]}]=""
    LOG_BOARD_CURRENT_INDEX=$((${#LOG_BOARD_LABELS[@]} - 1))
    render_dynamic_log_board
}

update_dynamic_stage() {
    local status="$1"
    local message="${2:-}"
    if [[ "${DYNAMIC_LOGS_ENABLED}" != "true" || ${LOG_BOARD_CURRENT_INDEX} -lt 0 ]]; then
        return
    fi
    if [[ -n "${message}" ]]; then
        LOG_BOARD_DETAILS[LOG_BOARD_CURRENT_INDEX]="${message}"
    fi
    if [[ "${status}" != "running" ]]; then
        LOG_BOARD_STATUSES[LOG_BOARD_CURRENT_INDEX]="${status}"
        LOG_BOARD_CURRENT_INDEX=-1
    fi
    render_dynamic_log_board
}

log_step() {
    local message="$1"
    if [[ "${DYNAMIC_LOGS_ENABLED}" == "true" ]]; then
        start_dynamic_stage "${message}"
        return
    fi
    echo ""
    printf "%s[%s]%s %s\n" "${LOG_COLOR_STEP}" "$(log_timestamp)" "${LOG_COLOR_RESET}" "${message}"
    printf "%s%s%s\n" "${LOG_COLOR_STEP}" "------------------------------------------------------------------------" "${LOG_COLOR_RESET}"
}

log_info() {
    local message="$1"
    if [[ "${DYNAMIC_LOGS_ENABLED}" == "true" ]]; then
        update_dynamic_stage "running" "${message}"
        return
    fi
    printf "  %s->%s %s\n" "${LOG_COLOR_INFO}" "${LOG_COLOR_RESET}" "${message}"
}

log_success() {
    local message="$1"
    if [[ "${DYNAMIC_LOGS_ENABLED}" == "true" ]]; then
        update_dynamic_stage "success" "${message}"
        return
    fi
    printf "  %sOK%s %s\n" "${LOG_COLOR_INFO}" "${LOG_COLOR_RESET}" "${message}"
}

log_skip() {
    local message="$1"
    if [[ "${DYNAMIC_LOGS_ENABLED}" == "true" ]]; then
        update_dynamic_stage "skipped" "${message}"
        return
    fi
    printf "  %sSKIP%s %s\n" "${LOG_COLOR_WARN}" "${LOG_COLOR_RESET}" "${message}"
}

fail() {
    local message="$1"
    if [[ "${MSH_FAILURE_RECORDED}" != "true" ]]; then
        emit_failure_cause "${message}"
    fi
    if [[ "${DYNAMIC_LOGS_ENABLED}" == "true" ]]; then
        update_dynamic_stage "failed" "${message}"
        echo ""
    fi
    printf "%sERROR:%s %s\n" "${LOG_COLOR_ERROR}" "${LOG_COLOR_RESET}" "${message}" >&2
    exit 1
}

require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        fail "Required command is not available: ${command_name}"
    fi
}

wait_for_http_json() {
    local url="$1"
    local timeout_seconds="$2"
    local started_at
    started_at="$(date +%s)"
    # Poll at 200ms granularity: fast services (adapters boot in <1s) no longer
    # pay a worst-case 1s wait; long-running starts (manager ~2s) add at most
    # a few extra no-op curl calls — cheap compared to 1s sleep grain.
    while true; do
        if curl -fsS "$url" >/dev/null 2>&1; then
            return 0
        fi
        local now elapsed
        now="$(date +%s)"
        elapsed=$((now - started_at))
        if (( elapsed >= timeout_seconds )); then
            return 1
        fi
        sleep 0.2
    done
}

HTTP_STATUS=""
HTTP_BODY=""
MSH_FAILURE_RECORDED="false"

compact_failure_value() {
    local raw
    raw="$(cat)"
    RAW_FAILURE_VALUE="${raw}" python3 - <<'PY'
import json
import os
import sys

raw = os.environ.get("RAW_FAILURE_VALUE", "").strip()
if not raw:
    print("")
    raise SystemExit(0)

try:
    value = json.loads(raw)
except json.JSONDecodeError:
    value = None

if value is not None:
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":"))

single_line = " ".join(raw.split())
limit = 600
if len(single_line) > limit:
    single_line = f"{single_line[:limit - 3]}..."
print(single_line)
PY
}

emit_phase_marker() {
    local phase_name="$1"
    local current="${2:-}"
    local total="${3:-}"
    if [[ -n "${current}" && -n "${total}" ]]; then
        echo "MSH_PHASE: ${phase_name} [${current}/${total}]"
    else
        echo "MSH_PHASE: ${phase_name}"
    fi
}

emit_test_progress_marker() {
    local current="$1"
    local total="$2"
    echo "MSH_TEST_PROGRESS: ${current}/${total}"
}

emit_failure_cause() {
    local cause="$1"
    local expected="${2:-}"
    local actual="${3:-}"
    printf 'MSH_FAILURE_CAUSE: %s\n' "$(printf '%s' "${cause}" | compact_failure_value)"
    if [[ -n "${expected}" ]]; then
        printf 'MSH_FAILURE_EXPECTED: %s\n' "$(printf '%s' "${expected}" | compact_failure_value)"
    fi
    if [[ -n "${actual}" ]]; then
        printf 'MSH_FAILURE_ACTUAL: %s\n' "$(printf '%s' "${actual}" | compact_failure_value)"
    fi
    MSH_FAILURE_RECORDED="true"
}

http_json() {
    local method="$1"
    local url="$2"
    local payload="${3:-}"
    local tmp_file
    tmp_file="$(mktemp)"
    if [[ -n "$payload" ]]; then
        HTTP_STATUS="$(curl -sS -o "$tmp_file" -w "%{http_code}" -X "$method" -H "Content-Type: application/json" --data-binary "$payload" "$url")"
    else
        HTTP_STATUS="$(curl -sS -o "$tmp_file" -w "%{http_code}" -X "$method" "$url")"
    fi
    HTTP_BODY="$(cat "$tmp_file")"
    rm -f "$tmp_file"
}

assert_status() {
    local expected="$1"
    if [[ "$HTTP_STATUS" != "$expected" ]]; then
        local actual
        actual="HTTP ${HTTP_STATUS}"
        if [[ -n "${HTTP_BODY}" ]]; then
            actual="${actual}; body=$(printf '%s' "${HTTP_BODY}" | compact_failure_value)"
        fi
        emit_failure_cause "HTTP status assertion failed" "HTTP ${expected}" "${actual}"
        echo "HTTP response body:"
        echo "$HTTP_BODY"
        fail "Expected HTTP $expected, got HTTP $HTTP_STATUS"
    fi
}

assert_json_expr() {
    local expression="$1"
    local description="$2"
    local assertion_output=""
    if ! assertion_output="$(PAYLOAD="$HTTP_BODY" JSON_EXPR="$expression" python3 - <<'PY' 2>&1
import json
import os
import sys

payload_text = os.environ["PAYLOAD"]
expression = os.environ["JSON_EXPR"]
payload = json.loads(payload_text)
safe_builtins = {
    "len": len,
    "sum": sum,
    "all": all,
    "any": any,
    "next": next,
    "isinstance": isinstance,
    "int": int,
    "str": str,
    "float": float,
    "list": list,
    "dict": dict,
    "bool": bool,
}
safe_globals = {
    "__builtins__": safe_builtins,
    "payload": payload,
}
result = eval(expression, safe_globals, {})
if not result:
    print(f"Assertion failed: {expression}", file=sys.stderr)
    sys.exit(1)
PY
    )"; then
        local actual
        actual="expression=$(printf '%s' "${expression}" | compact_failure_value)"
        if [[ -n "${assertion_output}" ]]; then
            actual="${actual}; details=$(printf '%s' "${assertion_output}" | compact_failure_value)"
        fi
        if [[ -n "${HTTP_BODY}" ]]; then
            actual="${actual}; payload=$(printf '%s' "${HTTP_BODY}" | compact_failure_value)"
        fi
        emit_failure_cause "JSON assertion failed" "${description}" "${actual}"
        echo "JSON payload:"
        echo "$HTTP_BODY"
        fail "Assertion failed: ${description}"
    fi
}

extract_json_value() {
    local expression="$1"
    PAYLOAD="$HTTP_BODY" JSON_EXPR="$expression" python3 - <<'PY'
import json
import os

payload = json.loads(os.environ["PAYLOAD"])
expression = os.environ["JSON_EXPR"]
safe_builtins = {
    "next": next,
    "int": int,
    "str": str,
    "len": len,
    "sum": sum,
    "all": all,
    "any": any,
}
value = eval(expression, {"__builtins__": safe_builtins, "payload": payload}, {})
if value is None:
    print("")
else:
    print(value)
PY
}
