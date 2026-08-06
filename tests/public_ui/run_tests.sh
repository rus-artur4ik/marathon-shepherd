#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SANDBOX_PARENT="${REPO_ROOT}/.msh-sandbox"
ARTIFACT_PARENT="${SCRIPT_DIR}/apks"
RUN_ROOT=""
LOG_ROOT=""
SANDBOX_ANDROID_HOME=""
SANDBOX_TMP=""
SANDBOX_USER_HOME=""
SELECTED_DEVICE_SERIAL="${ADB_SERIAL:-}"
PUBLIC_UI_AVD_NAME="${MSH_PUBLIC_UI_AVD:-Pixel_3a_API_34}"
EMULATOR_BINARY=""
STARTED_EMULATOR_SERIAL=""
CURRENT_LOG_FILE=""
CURRENT_STAGE_INDEX=-1
CURRENT_STAGE_LABEL=""
CURRENT_PROJECT_LABEL=""
CURRENT_TEST_KIND=""
CURRENT_COMMAND_TEXT=""
CURRENT_SCREEN_TITLE="Idle"
USE_DYNAMIC_UI=false
FINAL_REPORT_PRINTED=false
DRY_RUN=false
LOG_TAIL_LINES=10
DYNAMIC_SCREEN_LINES_RENDERED=0
DYNAMIC_SCREEN_BUFFER_LINES=()
PREVIOUS_DYNAMIC_SCREEN_BUFFER_LINES=()
DYNAMIC_REFRESH_INTERVAL_SECONDS="0.5"

LOG_COLOR_RESET=""
LOG_COLOR_INFO=""
LOG_COLOR_WARN=""
LOG_COLOR_ERROR=""
LOG_COLOR_ACCENT=""

STAGE_LABELS=()
STAGE_STATUSES=()
STAGE_DETAILS=()
CONFIG_LINES=()
PREREQ_LABELS=()
PREREQ_STATUSES=()
PREREQ_DETAILS=()
FAILED_STAGE_LABELS=()

ARCHITECTURE_SAMPLES_REPO_URL="https://github.com/android/architecture-samples.git"
ARCHITECTURE_SAMPLES_REPO_SHA="ee66e1526b84c026615df032c705842b7d2a521f"
ARCHITECTURE_SAMPLES_ANDROIDX_TEST_CORE_VERSION="1.7.0"
ARCHITECTURE_SAMPLES_ANDROIDX_TEST_EXT_VERSION="1.3.0"
ARCHITECTURE_SAMPLES_ANDROIDX_TEST_RULES_VERSION="1.7.0"
ARCHITECTURE_SAMPLES_ANDROIDX_TEST_RUNNER_VERSION="1.7.0"
ARCHITECTURE_SAMPLES_ANDROIDX_ESPRESSO_VERSION="3.7.0"
ARCHITECTURE_SAMPLES_ARTIFACT_REVISION="${ARCHITECTURE_SAMPLES_REPO_SHA}-androidx-test-${ARCHITECTURE_SAMPLES_ANDROIDX_ESPRESSO_VERSION}"
ARCHITECTURE_SAMPLES_CACHED_APP_APK_NAME="app-debug.apk"
ARCHITECTURE_SAMPLES_CACHED_TEST_APK_NAME="app-debug-androidTest.apk"
ARCHITECTURE_SAMPLES_METADATA_FILE_NAME="metadata.env"
ARCHITECTURE_SAMPLES_TEST_RUNNER_COMPONENT="com.example.android.architecture.blueprints.main.test/com.example.android.architecture.blueprints.todoapp.CustomTestRunner"
ARCHITECTURE_SAMPLES_ARTIFACT_DIR="${ARTIFACT_PARENT}/architecture-samples-${ARCHITECTURE_SAMPLES_ARTIFACT_REVISION}"
ARCHITECTURE_SAMPLES_APP_APK_PATH="${ARCHITECTURE_SAMPLES_ARTIFACT_DIR}/${ARCHITECTURE_SAMPLES_CACHED_APP_APK_NAME}"
ARCHITECTURE_SAMPLES_TEST_APK_PATH="${ARCHITECTURE_SAMPLES_ARTIFACT_DIR}/${ARCHITECTURE_SAMPLES_CACHED_TEST_APK_NAME}"
PREPARE_ARCHITECTURE_SAMPLES_SCRIPT="${REPO_ROOT}/scripts/build_test_apk.sh"

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Pinned public UI sample:
  1) android/architecture-samples @ ${ARCHITECTURE_SAMPLES_REPO_SHA}
     AndroidX test stack override: core ${ARCHITECTURE_SAMPLES_ANDROIDX_TEST_CORE_VERSION}, ext ${ARCHITECTURE_SAMPLES_ANDROIDX_TEST_EXT_VERSION}, rules ${ARCHITECTURE_SAMPLES_ANDROIDX_TEST_RULES_VERSION}, runner ${ARCHITECTURE_SAMPLES_ANDROIDX_TEST_RUNNER_VERSION}, espresso ${ARCHITECTURE_SAMPLES_ANDROIDX_ESPRESSO_VERSION}
     execution: adb install + am instrument
     required APKs: ${ARCHITECTURE_SAMPLES_CACHED_APP_APK_NAME}, ${ARCHITECTURE_SAMPLES_CACHED_TEST_APK_NAME}

Options:
  --device <serial>      Use a specific adb device/emulator serial
  --dry-run              Print the planned sample run and exit without installing APKs
  --plain                Disable dynamic TTY dashboard
  -h, --help             Show this help
EOF
}

init_colors() {
    if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
        LOG_COLOR_RESET=$'\033[0m'
        LOG_COLOR_INFO=$'\033[0;32m'
        LOG_COLOR_WARN=$'\033[1;33m'
        LOG_COLOR_ERROR=$'\033[1;31m'
        LOG_COLOR_ACCENT=$'\033[1;36m'
    fi
}

log_timestamp() {
    date '+%H:%M:%S'
}

status_badge() {
    local status="$1"
    case "${status}" in
        ok)
            printf "%s[OK]%s" "${LOG_COLOR_INFO}" "${LOG_COLOR_RESET}"
            ;;
        running)
            printf "%s[RUN]%s" "${LOG_COLOR_ACCENT}" "${LOG_COLOR_RESET}"
            ;;
        skip)
            printf "%s[SKIP]%s" "${LOG_COLOR_WARN}" "${LOG_COLOR_RESET}"
            ;;
        missing|failed)
            printf "%s[FAIL]%s" "${LOG_COLOR_ERROR}" "${LOG_COLOR_RESET}"
            ;;
        *)
            printf "[..]"
            ;;
    esac
}

plain_step() {
    printf "\n%s[%s]%s %s\n" "${LOG_COLOR_ACCENT}" "$(log_timestamp)" "${LOG_COLOR_RESET}" "$1"
    printf "%s\n" "------------------------------------------------------------------------"
}

plain_info() {
    printf "  %s->%s %s\n" "${LOG_COLOR_INFO}" "${LOG_COLOR_RESET}" "$1"
}

plain_warn() {
    printf "  %sWARN%s %s\n" "${LOG_COLOR_WARN}" "${LOG_COLOR_RESET}" "$1"
}

plain_fail() {
    printf "%sERROR:%s %s\n" "${LOG_COLOR_ERROR}" "${LOG_COLOR_RESET}" "$1" >&2
    exit 1
}

join_by() {
    local delimiter="$1"
    shift
    local result=""
    local item=""
    for item in "$@"; do
        if [[ -n "${result}" ]]; then
            result="${result}${delimiter}"
        fi
        result="${result}${item}"
    done
    printf "%s" "${result}"
}

append_config_line() {
    CONFIG_LINES+=("$1")
}

append_prereq_status() {
    PREREQ_LABELS+=("$1")
    PREREQ_STATUSES+=("$2")
    PREREQ_DETAILS+=("${3:-}")
}

start_stage() {
    STAGE_LABELS+=("$1")
    STAGE_STATUSES+=("running")
    STAGE_DETAILS+=("${2:-}")
    CURRENT_STAGE_INDEX=$((${#STAGE_LABELS[@]} - 1))
    CURRENT_STAGE_LABEL="$1"
    render_dynamic_screen
}

update_stage() {
    local status="$1"
    local detail="${2:-}"
    if [[ ${CURRENT_STAGE_INDEX} -lt 0 ]]; then
        return
    fi
    STAGE_STATUSES[${CURRENT_STAGE_INDEX}]="${status}"
    STAGE_DETAILS[${CURRENT_STAGE_INDEX}]="${detail}"
    CURRENT_STAGE_INDEX=-1
    CURRENT_STAGE_LABEL=""
    render_dynamic_screen
}

update_running_detail() {
    local detail="$1"
    if [[ ${CURRENT_STAGE_INDEX} -lt 0 ]]; then
        return
    fi
    STAGE_DETAILS[${CURRENT_STAGE_INDEX}]="${detail}"
    render_dynamic_screen
}

print_static_report() {
    if [[ "${FINAL_REPORT_PRINTED}" == "true" ]]; then
        return
    fi
    FINAL_REPORT_PRINTED=true
    printf "%s\n" "========================================================================"
    printf "%s\n" "Public UI Sample"
    printf "%s\n" "========================================================================"
    printf "%s\n" "Configuration"
    printf "%s\n" "------------------------------------------------------------------------"
    local line=""
    for line in "${CONFIG_LINES[@]}"; do
        printf "  %s\n" "${line}"
    done
    printf "\n%s\n" "Prerequisites"
    printf "%s\n" "------------------------------------------------------------------------"
    local index=0
    local detail=""
    for ((index = 0; index < ${#PREREQ_LABELS[@]}; index++)); do
        detail="${PREREQ_DETAILS[index]}"
        if [[ -n "${detail}" ]]; then
            printf "  %s %s :: %s\n" "$(status_badge "${PREREQ_STATUSES[index]}")" "${PREREQ_LABELS[index]}" "${detail}"
        else
            printf "  %s %s\n" "$(status_badge "${PREREQ_STATUSES[index]}")" "${PREREQ_LABELS[index]}"
        fi
    done
    printf "\n%s\n" "Stages"
    printf "%s\n" "------------------------------------------------------------------------"
    for ((index = 0; index < ${#STAGE_LABELS[@]}; index++)); do
        detail="${STAGE_DETAILS[index]}"
        if [[ -n "${detail}" ]]; then
            printf "  %s %s :: %s\n" "$(status_badge "${STAGE_STATUSES[index]}")" "${STAGE_LABELS[index]}" "${detail}"
        else
            printf "  %s %s\n" "$(status_badge "${STAGE_STATUSES[index]}")" "${STAGE_LABELS[index]}"
        fi
    done
}

clear_dynamic_screen() {
    local index=0
    if [[ "${USE_DYNAMIC_UI}" != "true" ]] || (( DYNAMIC_SCREEN_LINES_RENDERED == 0 )); then
        return
    fi
    printf '\033[%dA' "${DYNAMIC_SCREEN_LINES_RENDERED}"
    for ((index = 0; index < DYNAMIC_SCREEN_LINES_RENDERED; index++)); do
        printf '\r\033[2K'
        if (( index + 1 < DYNAMIC_SCREEN_LINES_RENDERED )); then
            printf '\033[1B'
        fi
    done
    if (( DYNAMIC_SCREEN_LINES_RENDERED > 1 )); then
        printf '\033[%dA' "$((DYNAMIC_SCREEN_LINES_RENDERED - 1))"
    fi
    DYNAMIC_SCREEN_LINES_RENDERED=0
}

append_dynamic_screen_line() {
    DYNAMIC_SCREEN_BUFFER_LINES+=("$1")
}

append_dynamic_status_lines() {
    local section_name="$1"
    local -n labels_ref="$2"
    local -n statuses_ref="$3"
    local -n details_ref="$4"
    local index=0
    local detail=""
    append_dynamic_screen_line "${section_name}"
    append_dynamic_screen_line "------------------------------------------------------------------------"
    for ((index = 0; index < ${#labels_ref[@]}; index++)); do
        detail="${details_ref[index]}"
        if [[ -n "${detail}" ]]; then
            append_dynamic_screen_line "  $(status_badge "${statuses_ref[index]}") ${labels_ref[index]} :: ${detail}"
        else
            append_dynamic_screen_line "  $(status_badge "${statuses_ref[index]}") ${labels_ref[index]}"
        fi
    done
}

build_dynamic_screen_buffer() {
    local line=""
    DYNAMIC_SCREEN_BUFFER_LINES=()
    append_dynamic_screen_line "Public UI Sample"
    append_dynamic_screen_line "========================================================================"
    append_dynamic_screen_line "Configuration"
    append_dynamic_screen_line "------------------------------------------------------------------------"
    for line in "${CONFIG_LINES[@]}"; do
        append_dynamic_screen_line "  ${line}"
    done
    append_dynamic_screen_line ""
    append_dynamic_status_lines "Prerequisites" PREREQ_LABELS PREREQ_STATUSES PREREQ_DETAILS
    append_dynamic_screen_line ""
    append_dynamic_status_lines "Stages" STAGE_LABELS STAGE_STATUSES STAGE_DETAILS
    append_dynamic_screen_line ""
    append_dynamic_screen_line "Live logs (last ${LOG_TAIL_LINES} lines)"
    append_dynamic_screen_line "------------------------------------------------------------------------"
    if [[ -n "${CURRENT_LOG_FILE}" && -f "${CURRENT_LOG_FILE}" && -s "${CURRENT_LOG_FILE}" ]]; then
        while IFS= read -r line; do
            append_dynamic_screen_line "  ${line}"
        done < <(tail -n "${LOG_TAIL_LINES}" "${CURRENT_LOG_FILE}" 2>/dev/null || true)
    else
        append_dynamic_screen_line "  (waiting for output)"
    fi
}

render_dynamic_screen() {
    if [[ "${USE_DYNAMIC_UI}" != "true" ]]; then
        return
    fi
    build_dynamic_screen_buffer
    local previous_count=${#PREVIOUS_DYNAMIC_SCREEN_BUFFER_LINES[@]}
    local current_count=${#DYNAMIC_SCREEN_BUFFER_LINES[@]}
    local max_count="${current_count}"
    local line=""
    local index=0
    local previous_line=""
    local current_line=""
    if (( previous_count == 0 )); then
        for line in "${DYNAMIC_SCREEN_BUFFER_LINES[@]}"; do
            printf "%s\n" "${line}"
        done
        DYNAMIC_SCREEN_LINES_RENDERED=${current_count}
        PREVIOUS_DYNAMIC_SCREEN_BUFFER_LINES=("${DYNAMIC_SCREEN_BUFFER_LINES[@]}")
        return
    fi
    if (( previous_count > max_count )); then
        max_count=${previous_count}
    fi
    printf '\033[%dA' "${DYNAMIC_SCREEN_LINES_RENDERED}"
    for ((index = 0; index < max_count; index++)); do
        previous_line="${PREVIOUS_DYNAMIC_SCREEN_BUFFER_LINES[index]:-}"
        current_line="${DYNAMIC_SCREEN_BUFFER_LINES[index]:-}"
        if [[ "${current_line}" != "${previous_line}" ]]; then
            printf '\r\033[2K'
            if [[ -n "${current_line}" ]]; then
                printf "%s" "${current_line}"
            fi
        fi
        printf '\n'
    done
    DYNAMIC_SCREEN_LINES_RENDERED=${max_count}
    PREVIOUS_DYNAMIC_SCREEN_BUFFER_LINES=("${DYNAMIC_SCREEN_BUFFER_LINES[@]}")
}

cleanup() {
    local exit_code="$?"
    local cleanup_message=""
    trap - EXIT INT TERM
    clear_dynamic_screen
    if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
        print_static_report
        printf "\n"
    fi
    if [[ -n "${STARTED_EMULATOR_SERIAL}" ]]; then
        adb -s "${STARTED_EMULATOR_SERIAL}" emu kill >/dev/null 2>&1 || true
    fi
    if [[ -n "${RUN_ROOT}" && -d "${RUN_ROOT}" ]]; then
        if ! remove_path_with_retries "${RUN_ROOT}"; then
            cleanup_message="failed to remove sandbox run root ${RUN_ROOT}"
        fi
    fi
    if [[ -d "${SANDBOX_PARENT}" ]] && [[ -z "$(find "${SANDBOX_PARENT}" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
        rmdir "${SANDBOX_PARENT}" >/dev/null 2>&1 || true
    fi
    if [[ -n "${cleanup_message}" ]]; then
        if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
            printf "%sWARN%s %s\n" "${LOG_COLOR_WARN}" "${LOG_COLOR_RESET}" "${cleanup_message}"
        else
            plain_warn "${cleanup_message}"
        fi
    fi
    return "${exit_code}"
}

trap cleanup EXIT INT TERM

remove_path_with_retries() {
    local path="$1"
    local attempt=0
    while [[ ${attempt} -lt 5 ]]; do
        if [[ ! -e "${path}" ]]; then
            return 0
        fi
        rm -rf "${path}" >/dev/null 2>&1 || true
        if [[ ! -e "${path}" ]]; then
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    return 1
}

prune_stale_sandboxes() {
    local stale_dir=""
    if [[ ! -d "${SANDBOX_PARENT}" ]]; then
        return 0
    fi
    while IFS= read -r stale_dir; do
        if [[ -z "${stale_dir}" ]]; then
            continue
        fi
        remove_path_with_retries "${stale_dir}" || true
    done < <(find "${SANDBOX_PARENT}" -mindepth 1 -maxdepth 1 -type d -name 'public_ui.*' -print 2>/dev/null || true)
    return 0
}

record_matrix_failure() {
    local stage_label="$1"
    FAILED_STAGE_LABELS+=("${stage_label}")
    if [[ "${USE_DYNAMIC_UI}" != "true" ]]; then
        plain_warn "Stage failed and the sample run will continue: ${stage_label}"
    fi
}

run_best_effort_step() {
    local stage_label="$1"
    shift
    if "$@"; then
        return 0
    fi
    record_matrix_failure "${stage_label}"
    return 0
}

record_prerequisite_command() {
    local label="$1"
    local command_name="$2"
    if command -v "${command_name}" >/dev/null 2>&1; then
        append_prereq_status "${label}" "ok"
        return 0
    fi
    append_prereq_status "${label}" "missing" "command not found"
    return 1
}

resolve_emulator_binary() {
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
    local candidate="${sdk_root}/emulator/emulator"
    if [[ -x "${candidate}" ]]; then
        printf "%s" "${candidate}"
        return 0
    fi
    if command -v emulator >/dev/null 2>&1; then
        command -v emulator
        return 0
    fi
    return 1
}

list_connected_adb_devices() {
    adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

find_connected_emulator_serial() {
    local serial=""
    while IFS= read -r serial; do
        if [[ "${serial}" == emulator-* ]]; then
            printf "%s" "${serial}"
            return 0
        fi
    done < <(list_connected_adb_devices)
    return 1
}

wait_for_device_boot() {
    local serial="$1"
    local started_at
    local boot_completed=""
    local elapsed=0
    started_at="$(date +%s)"
    adb -s "${serial}" wait-for-device >/dev/null 2>&1
    while true; do
        boot_completed="$(adb -s "${serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
        if [[ "${boot_completed}" == "1" ]]; then
            return 0
        fi
        elapsed=$(( $(date +%s) - started_at ))
        if (( elapsed >= 180 )); then
            return 1
        fi
        sleep 1
    done
}

start_default_emulator() {
    local emulator_log="${LOG_ROOT}/start-emulator.log"
    local detected_serial=""
    local started_at
    local elapsed=0
    local spinner_frames='-\|/'
    local spinner_index=0
    CURRENT_COMMAND_TEXT="${EMULATOR_BINARY} -avd ${PUBLIC_UI_AVD_NAME} -no-window -no-snapshot -no-boot-anim"
    CURRENT_LOG_FILE="${emulator_log}"
    if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
        start_stage "Start emulator ${PUBLIC_UI_AVD_NAME}" "booting"
    else
        plain_step "Start emulator ${PUBLIC_UI_AVD_NAME}" >&2
    fi
    nohup "${EMULATOR_BINARY}" -avd "${PUBLIC_UI_AVD_NAME}" -no-window -no-snapshot -no-boot-anim >"${emulator_log}" 2>&1 &
    started_at="$(date +%s)"
    while true; do
        if detected_serial="$(find_connected_emulator_serial)"; then
            if wait_for_device_boot "${detected_serial}"; then
                STARTED_EMULATOR_SERIAL="${detected_serial}"
                if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
                    update_stage "ok" "${detected_serial} booted"
                else
                    plain_info "${detected_serial} booted" >&2
                fi
                printf "%s" "${detected_serial}"
                return 0
            fi
        fi
        elapsed=$(( $(date +%s) - started_at ))
        if (( elapsed >= 240 )); then
            if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
                update_stage "failed" "timed out waiting for ${PUBLIC_UI_AVD_NAME}"
            else
                plain_warn "timed out waiting for ${PUBLIC_UI_AVD_NAME}" >&2
            fi
            return 1
        fi
        if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
            update_running_detail "booting (${elapsed}s, ${spinner_frames:${spinner_index}:1})"
            spinner_index=$(((spinner_index + 1) % 4))
        fi
        sleep "${DYNAMIC_REFRESH_INTERVAL_SECONDS}"
    done
}

resolve_device_serial() {
    local serial=""

    if [[ -n "${SELECTED_DEVICE_SERIAL}" ]]; then
        local found=false
        while IFS= read -r serial; do
            if [[ "${serial}" == "${SELECTED_DEVICE_SERIAL}" ]]; then
                found=true
                break
            fi
        done < <(list_connected_adb_devices)
        if [[ "${found}" != "true" ]]; then
            append_prereq_status "adb device" "missing" "requested serial ${SELECTED_DEVICE_SERIAL} is not connected"
            return 1
        fi
        append_prereq_status "adb device" "ok" "${SELECTED_DEVICE_SERIAL}"
        return 0
    fi

    if serial="$(find_connected_emulator_serial)"; then
        SELECTED_DEVICE_SERIAL="${serial}"
        append_prereq_status "adb device" "ok" "${SELECTED_DEVICE_SERIAL} (running emulator)"
        return 0
    fi

    if ! EMULATOR_BINARY="$(resolve_emulator_binary)"; then
        append_prereq_status "emulator" "missing" "Android SDK emulator binary not found"
        append_prereq_status "adb device" "missing" "no running emulator and emulator binary is unavailable"
        return 1
    fi
    append_prereq_status "emulator" "ok" "${EMULATOR_BINARY}"
    if ! "${EMULATOR_BINARY}" -list-avds | grep -Fxq "${PUBLIC_UI_AVD_NAME}"; then
        append_prereq_status "android avd" "missing" "${PUBLIC_UI_AVD_NAME}"
        append_prereq_status "adb device" "missing" "no running emulator and default AVD is unavailable"
        return 1
    fi
    append_prereq_status "android avd" "ok" "${PUBLIC_UI_AVD_NAME}"
    if ! SELECTED_DEVICE_SERIAL="$(start_default_emulator)"; then
        append_prereq_status "adb device" "missing" "failed to boot ${PUBLIC_UI_AVD_NAME}"
        return 1
    fi
    append_prereq_status "adb device" "ok" "${SELECTED_DEVICE_SERIAL} (started ${PUBLIC_UI_AVD_NAME})"
    return 0
}

build_enabled_kinds_line() {
    local values=("architecture-samples instrumentation")
    printf "%s" "$(join_by ", " "${values[@]}")"
}

prepare_configuration() {
    append_config_line "Sandbox root: ${SANDBOX_PARENT}"
    append_config_line "Pinned APK root: ${ARTIFACT_PARENT}"
    append_config_line "Pinned repo: android/architecture-samples @ ${ARCHITECTURE_SAMPLES_REPO_SHA}"
    append_config_line "AndroidX test stack override: core ${ARCHITECTURE_SAMPLES_ANDROIDX_TEST_CORE_VERSION}, ext ${ARCHITECTURE_SAMPLES_ANDROIDX_TEST_EXT_VERSION}, rules ${ARCHITECTURE_SAMPLES_ANDROIDX_TEST_RULES_VERSION}, runner ${ARCHITECTURE_SAMPLES_ANDROIDX_TEST_RUNNER_VERSION}, espresso ${ARCHITECTURE_SAMPLES_ANDROIDX_ESPRESSO_VERSION}"
    append_config_line "Output mode: $([[ "${USE_DYNAMIC_UI}" == "true" ]] && printf 'dynamic' || printf 'plain')"
    append_config_line "Dry-run: $([[ "${DRY_RUN}" == "true" ]] && printf 'enabled' || printf 'disabled')"
    append_config_line "Selected device: ${SELECTED_DEVICE_SERIAL:-auto-start emulator}"
    append_config_line "Default AVD: ${PUBLIC_UI_AVD_NAME}"
    append_config_line "Test kinds: $(build_enabled_kinds_line)"
    append_config_line "Sample :: android/architecture-samples"
    append_config_line "Execution mode: install cached APKs and run adb instrumentation"
    append_config_line "APK preparation: ${PREPARE_ARCHITECTURE_SAMPLES_SCRIPT}"
    append_config_line "Cleanup strategy: remove sandbox files and uninstall packages added during connected suites"
}

prepare_sandbox() {
    mkdir -p "${SANDBOX_PARENT}"
    prune_stale_sandboxes
    RUN_ROOT="$(mktemp -d "${SANDBOX_PARENT}/public_ui.XXXXXX")"
    LOG_ROOT="${RUN_ROOT}/logs"
    SANDBOX_ANDROID_HOME="${RUN_ROOT}/android-user-home"
    SANDBOX_TMP="${RUN_ROOT}/tmp"
    SANDBOX_USER_HOME="${RUN_ROOT}/user-home"
    mkdir -p "${LOG_ROOT}" "${SANDBOX_ANDROID_HOME}" "${SANDBOX_TMP}" "${SANDBOX_USER_HOME}"
}

run_logged_command() {
    local stage_label="$1"
    local workdir="$2"
    shift 2
    local slug
    local log_file
    local start_ts
    local now
    local elapsed=0
    local pid
    local spinner_frames='-\|/'
    local spinner_index=0
    slug="$(printf "%s" "${stage_label}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-')"
    log_file="${LOG_ROOT}/${slug}.log"
    CURRENT_LOG_FILE="${log_file}"
    CURRENT_COMMAND_TEXT="$(join_by " " "$@")"
    if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
        start_stage "${stage_label}" "starting"
        start_ts="$(date +%s)"
        (
            cd "${workdir}"
            "$@"
        ) >"${log_file}" 2>&1 &
        pid="$!"
        while kill -0 "${pid}" >/dev/null 2>&1; do
            now="$(date +%s)"
            elapsed=$((now - start_ts))
            update_running_detail "running (${elapsed}s, ${spinner_frames:${spinner_index}:1})"
            spinner_index=$(((spinner_index + 1) % 4))
            sleep "${DYNAMIC_REFRESH_INTERVAL_SECONDS}"
        done
        if wait "${pid}"; then
            update_stage "ok" "completed"
            return 0
        fi
        update_stage "failed" "see log tail below"
        return 1
    fi

    plain_step "${stage_label}"
    (
        cd "${workdir}"
        "$@"
    ) 2>&1 | tee "${log_file}"
}

has_architecture_samples_apks() {
    [[ -f "${ARCHITECTURE_SAMPLES_APP_APK_PATH}" && -f "${ARCHITECTURE_SAMPLES_TEST_APK_PATH}" ]]
}

snapshot_packages() {
    local output_file="$1"
    adb -s "${SELECTED_DEVICE_SERIAL}" shell pm list packages | sed 's/^package://' | sort > "${output_file}"
}

cleanup_new_packages() {
    local before_file="$1"
    local after_file="$2"
    local package_name=""
    if [[ ! -f "${before_file}" || ! -f "${after_file}" ]]; then
        return 0
    fi
    while IFS= read -r package_name; do
        if [[ -n "${package_name}" ]]; then
            adb -s "${SELECTED_DEVICE_SERIAL}" uninstall "${package_name}" >/dev/null 2>&1 || true
        fi
    done < <(comm -13 "${before_file}" "${after_file}" || true)
}

run_connected_apk_instrumentation() {
    local stage_label="$1"
    local artifact_root="$2"
    local before_file="${RUN_ROOT}/before-packages.$$.txt"
    local after_file="${RUN_ROOT}/after-packages.$$.txt"
    local rc=0
    snapshot_packages "${before_file}" || true
    if run_logged_command "${stage_label} :: install app APK" "${artifact_root}" adb -s "${SELECTED_DEVICE_SERIAL}" install -r "${ARCHITECTURE_SAMPLES_APP_APK_PATH}"; then
        :
    else
        rc=$?
    fi
    if [[ ${rc} -eq 0 ]]; then
        if run_logged_command "${stage_label} :: install test APK" "${artifact_root}" adb -s "${SELECTED_DEVICE_SERIAL}" install -r -t "${ARCHITECTURE_SAMPLES_TEST_APK_PATH}"; then
            :
        else
            rc=$?
        fi
    fi
    if [[ ${rc} -eq 0 ]]; then
        if run_logged_command "${stage_label} :: instrumentation" "${artifact_root}" adb -s "${SELECTED_DEVICE_SERIAL}" shell am instrument -w "${ARCHITECTURE_SAMPLES_TEST_RUNNER_COMPONENT}"; then
            :
        else
            rc=$?
        fi
    fi
    snapshot_packages "${after_file}" || true
    cleanup_new_packages "${before_file}" "${after_file}"
    rm -f "${before_file}" "${after_file}"
    return "${rc}"
}

run_architecture_samples_suite() {
    local artifact_root="$1"
    CURRENT_PROJECT_LABEL="android/architecture-samples"
    CURRENT_TEST_KIND="instrumentation"
    run_best_effort_step \
        "Architecture Samples :: cached APK instrumentation" \
        run_connected_apk_instrumentation \
        "Architecture Samples" \
        "${artifact_root}"
}

print_plain_configuration_stage() {
    plain_step "Test configuration"
    local line=""
    for line in "${CONFIG_LINES[@]}"; do
        plain_info "${line}"
    done
}

print_plain_prerequisites_stage() {
    plain_step "Checking prerequisites"
    local index=0
    local detail=""
    for ((index = 0; index < ${#PREREQ_LABELS[@]}; index++)); do
        detail="${PREREQ_DETAILS[index]}"
        if [[ -n "${detail}" ]]; then
            plain_info "$(status_badge "${PREREQ_STATUSES[index]}") ${PREREQ_LABELS[index]} :: ${detail}"
        else
            plain_info "$(status_badge "${PREREQ_STATUSES[index]}") ${PREREQ_LABELS[index]}"
        fi
    done
}

validate_prerequisites() {
    local ok=true
    if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
        start_stage "Prerequisites" "checking required tools and adb device"
    fi
    record_prerequisite_command "git" "git" || ok=false
    record_prerequisite_command "java" "java" || ok=false
    record_prerequisite_command "adb" "adb" || ok=false
    if [[ "${DRY_RUN}" == "true" ]]; then
        append_prereq_status "adb device" "skip" "dry-run does not probe adb state"
    else
        resolve_device_serial || ok=false
    fi
    if has_architecture_samples_apks; then
        append_prereq_status "prebuilt APKs" "ok" "${ARCHITECTURE_SAMPLES_ARTIFACT_DIR}"
    else
        append_prereq_status "prebuilt APKs" "missing" "run ${PREPARE_ARCHITECTURE_SAMPLES_SCRIPT}"
        ok=false
    fi
    if [[ "${ok}" != "true" ]]; then
        if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
            update_stage "failed" "missing prerequisites"
            clear_dynamic_screen
            print_static_report
            printf "\n"
        else
            print_plain_prerequisites_stage
        fi
        plain_fail "Missing prerequisites for public UI sample"
    fi
    if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
        update_stage "ok" "all required tools are available"
    fi
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)
            SELECTED_DEVICE_SERIAL="${2:-}"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --plain)
            USE_DYNAMIC_UI=false
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            plain_fail "Unknown argument: $1"
            ;;
    esac
done

init_colors
if [[ -t 1 && -z "${NO_COLOR:-}" && "${DRY_RUN}" != "true" ]]; then
    USE_DYNAMIC_UI=true
fi

prepare_configuration
prepare_sandbox

if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
    start_stage "Configuration" "collecting pinned sample definition"
    update_stage "ok" "pinned repo, sandbox mode and test kind recorded"
fi

if [[ "${USE_DYNAMIC_UI}" != "true" ]]; then
    print_plain_configuration_stage
fi

validate_prerequisites
if [[ "${USE_DYNAMIC_UI}" != "true" ]]; then
    print_plain_prerequisites_stage
fi

if [[ "${DRY_RUN}" == "true" ]]; then
    if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
        start_stage "Prebuilt APK check" "dry-run"
        update_stage "skip" "${ARCHITECTURE_SAMPLES_ARTIFACT_DIR}"
    else
        plain_step "Prebuilt APK check"
        plain_warn "dry-run: would use prebuilt APKs from ${ARCHITECTURE_SAMPLES_ARTIFACT_DIR}"
    fi
    if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
        start_stage "Dry-run summary" "planned sample run printed"
        update_stage "ok" "no commands executed"
    else
        plain_step "Dry-run summary"
        plain_info "No repositories were cloned and no tests were executed."
    fi
    exit 0
fi

run_architecture_samples_suite "${ARCHITECTURE_SAMPLES_ARTIFACT_DIR}"

if [[ ${#FAILED_STAGE_LABELS[@]} -gt 0 ]]; then
    failed_stage=""
    if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
        clear_dynamic_screen
        print_static_report
        printf "\nFailed stages:\n"
        printf "%s\n" "------------------------------------------------------------------------"
        for failed_stage in "${FAILED_STAGE_LABELS[@]}"; do
            printf "  - %s\n" "${failed_stage}"
        done
        printf "\n"
    else
        plain_step "Final summary"
        plain_warn "Pinned public UI sample completed with failures."
        for failed_stage in "${FAILED_STAGE_LABELS[@]}"; do
            plain_warn "${failed_stage}"
        done
    fi
    exit 1
fi

if [[ "${USE_DYNAMIC_UI}" == "true" ]]; then
    clear_dynamic_screen
    print_static_report
    printf "\n"
else
    plain_step "Final summary"
    plain_info "The pinned public UI sample completed successfully."
fi
