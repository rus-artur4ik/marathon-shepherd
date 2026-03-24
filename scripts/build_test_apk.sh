#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SANDBOX_PARENT="${REPO_ROOT}/.msh-sandbox"
RUN_ROOT=""
LOG_ROOT=""
SANDBOX_ANDROID_HOME=""
SANDBOX_TMP=""
SANDBOX_USER_HOME=""
REFRESH=false

ARCHITECTURE_SAMPLES_REPO_URL="https://github.com/android/architecture-samples.git"
ARCHITECTURE_SAMPLES_REPO_SHA="ee66e1526b84c026615df032c705842b7d2a521f"
ARCHITECTURE_SAMPLES_APP_APK_RELATIVE_PATH="app/build/outputs/apk/debug/app-debug.apk"
ARCHITECTURE_SAMPLES_TEST_APK_RELATIVE_PATH="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
ARTIFACT_ROOT="${REPO_ROOT}/tests/public_ui/apks/architecture-samples-${ARCHITECTURE_SAMPLES_REPO_SHA}"
APP_APK_PATH="${ARTIFACT_ROOT}/app-debug.apk"
TEST_APK_PATH="${ARTIFACT_ROOT}/app-debug-androidTest.apk"
METADATA_PATH="${ARTIFACT_ROOT}/metadata.env"
LEGACY_ARTIFACT_ROOT="${REPO_ROOT}/.msh-public-ui-cache/architecture-samples-${ARCHITECTURE_SAMPLES_REPO_SHA}-artifacts"
LEGACY_CACHE_PARENT="${REPO_ROOT}/.msh-public-ui-cache"

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Builds pinned android/architecture-samples APKs and copies only the final artifacts into:
  ${ARTIFACT_ROOT}

Options:
  --refresh   Rebuild APKs even if the target files already exist
  -h, --help  Show this help
EOF
}

log_step() {
    printf "\n[%s] %s\n" "$(date '+%H:%M:%S')" "$1"
    printf "%s\n" "------------------------------------------------------------------------"
}

log_info() {
    printf "  -> %s\n" "$1"
}

cleanup() {
    local exit_code="$?"
    trap - EXIT INT TERM
    if [[ -n "${RUN_ROOT}" && -d "${RUN_ROOT}" ]]; then
        rm -rf "${RUN_ROOT}" >/dev/null 2>&1 || true
    fi
    if [[ -d "${SANDBOX_PARENT}" ]] && [[ -z "$(find "${SANDBOX_PARENT}" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
        rmdir "${SANDBOX_PARENT}" >/dev/null 2>&1 || true
    fi
    exit "${exit_code}"
}

trap cleanup EXIT INT TERM

migrate_legacy_artifacts_if_present() {
    if [[ ! -d "${LEGACY_ARTIFACT_ROOT}" ]]; then
        return 0
    fi
    mkdir -p "${ARTIFACT_ROOT}"
    if [[ -f "${LEGACY_ARTIFACT_ROOT}/app-debug.apk" ]]; then
        cp "${LEGACY_ARTIFACT_ROOT}/app-debug.apk" "${APP_APK_PATH}"
    fi
    if [[ -f "${LEGACY_ARTIFACT_ROOT}/app-debug-androidTest.apk" ]]; then
        cp "${LEGACY_ARTIFACT_ROOT}/app-debug-androidTest.apk" "${TEST_APK_PATH}"
    fi
    if [[ -f "${LEGACY_ARTIFACT_ROOT}/metadata.env" ]]; then
        cp "${LEGACY_ARTIFACT_ROOT}/metadata.env" "${METADATA_PATH}"
    fi
    rm -rf "${LEGACY_ARTIFACT_ROOT}" >/dev/null 2>&1 || true
    if [[ -d "${LEGACY_CACHE_PARENT}" ]] && [[ -z "$(find "${LEGACY_CACHE_PARENT}" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
        rmdir "${LEGACY_CACHE_PARENT}" >/dev/null 2>&1 || true
    fi
}

write_metadata() {
    cat > "${METADATA_PATH}" <<EOF
repo=android/architecture-samples
sha=${ARCHITECTURE_SAMPLES_REPO_SHA}
app_apk=$(basename "${APP_APK_PATH}")
test_apk=$(basename "${TEST_APK_PATH}")
runner=com.example.android.architecture.blueprints.main.test/com.example.android.architecture.blueprints.todoapp.CustomTestRunner
EOF
}

prepare_workspace() {
    mkdir -p "${SANDBOX_PARENT}"
    RUN_ROOT="$(mktemp -d "${SANDBOX_PARENT}/build_test_apk.XXXXXX")"
    LOG_ROOT="${RUN_ROOT}/logs"
    SANDBOX_ANDROID_HOME="${RUN_ROOT}/android-user-home"
    SANDBOX_TMP="${RUN_ROOT}/tmp"
    SANDBOX_USER_HOME="${RUN_ROOT}/user-home"
    mkdir -p "${LOG_ROOT}" "${SANDBOX_ANDROID_HOME}" "${SANDBOX_TMP}" "${SANDBOX_USER_HOME}" "${ARTIFACT_ROOT}"
}

run_logged_command() {
    local stage_label="$1"
    local workdir="$2"
    shift 2
    local slug
    local log_file
    slug="$(printf "%s" "${stage_label}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-')"
    log_file="${LOG_ROOT}/${slug}.log"
    log_step "${stage_label}"
    (
        cd "${workdir}"
        "$@"
    ) 2>&1 | tee "${log_file}"
}

run_gradle_command() {
    local stage_label="$1"
    local workdir="$2"
    shift 2
    local env_args=(
        "GRADLE_USER_HOME=${RUN_ROOT}/gradle-home"
        "ANDROID_USER_HOME=${SANDBOX_ANDROID_HOME}"
        "TMPDIR=${SANDBOX_TMP}"
        "GRADLE_OPTS=-Dorg.gradle.daemon=false -Duser.home=${SANDBOX_USER_HOME}"
    )
    run_logged_command "${stage_label}" "${workdir}" env "${env_args[@]}" ./gradlew --no-daemon --console=plain "$@"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --refresh)
            REFRESH=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf "ERROR: Unknown argument: %s\n" "$1" >&2
            exit 1
            ;;
    esac
done

migrate_legacy_artifacts_if_present

if [[ "${REFRESH}" != "true" && -f "${APP_APK_PATH}" && -f "${TEST_APK_PATH}" ]]; then
    log_step "Prebuilt APKs"
    log_info "Reusing existing artifacts from ${ARTIFACT_ROOT}"
    if [[ ! -f "${METADATA_PATH}" ]]; then
        write_metadata
    fi
    exit 0
fi

prepare_workspace

CHECKOUT_ROOT="${RUN_ROOT}/architecture-samples"

run_logged_command \
    "Clone android/architecture-samples" \
    "${RUN_ROOT}" \
    git clone --filter=blob:none --depth 1 --branch main "${ARCHITECTURE_SAMPLES_REPO_URL}" "${CHECKOUT_ROOT}"

run_logged_command \
    "Checkout pinned revision" \
    "${CHECKOUT_ROOT}" \
    git fetch --depth 1 origin "${ARCHITECTURE_SAMPLES_REPO_SHA}"

run_logged_command \
    "Pin android/architecture-samples" \
    "${CHECKOUT_ROOT}" \
    git checkout --force "${ARCHITECTURE_SAMPLES_REPO_SHA}"

run_gradle_command \
    "Build debug + androidTest APKs" \
    "${CHECKOUT_ROOT}" \
    :app:assembleDebug \
    :app:assembleDebugAndroidTest

log_step "Copy APK artifacts"
cp "${CHECKOUT_ROOT}/${ARCHITECTURE_SAMPLES_APP_APK_RELATIVE_PATH}" "${APP_APK_PATH}"
cp "${CHECKOUT_ROOT}/${ARCHITECTURE_SAMPLES_TEST_APK_RELATIVE_PATH}" "${TEST_APK_PATH}"
write_metadata
log_info "Saved app APK to ${APP_APK_PATH}"
log_info "Saved test APK to ${TEST_APK_PATH}"
log_info "Saved metadata to ${METADATA_PATH}"
