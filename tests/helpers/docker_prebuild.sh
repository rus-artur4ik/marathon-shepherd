#!/usr/bin/env bash
# Pre-build every external dependency (docker images + config downloads) used
# by smoke / integration / scale stages in a single parallel batch.
#
# What it does upfront:
#   - builds all adapter+manager test images (tagged for both smoke and
#     integration compose files via double `-t` flags)
#   - builds cloud-orchestrator from source (Go toolchain pull + git clone)
#   - downloads the cloud-orchestrator conf.toml from Google Artifact Registry
#
# Downstream stages read:
#   MSH_SKIP_DOCKER_BUILD=1                — don't rebuild images
#   MSH_PREBUILT_ORCHESTRATOR_CONFIG=<path> — reuse conf.toml
#
# Idempotent: BuildKit layer cache + conditional config redownload. Safe to
# rerun after a partial failure.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# BuildKit is the default on modern Docker but be explicit — older engines silently fall back.
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1

# Shared workspace for pre-downloaded artifacts (orchestrator config).
PREBUILD_CACHE_DIR="${REPO_ROOT}/.msh-sandbox/prebuild-cache"
mkdir -p "${PREBUILD_CACHE_DIR}"
mkdir -p "${REPO_ROOT}/.msh-sandbox/prebuild-logs"

log() {
    printf '[prebuild] %s\n' "$*"
}

# Each image is tagged with multiple aliases so both the smoke (msh-*-smoke)
# and integration (msh-*-integration) compose files find an already-built
# image and skip their own `docker compose build` step.
build_one() {
    # $1 = dockerfile path (relative to REPO_ROOT)
    # $2 = space-separated list of tags
    # $3 = extra docker build args (e.g. build-args) or empty
    local dockerfile="$1"
    local tags="$2"
    local extra_args="${3:-}"
    local tag_flags=""
    for tag in ${tags}; do
        tag_flags+=" -t ${tag}"
    done
    log "building${tag_flags} <- ${dockerfile} ${extra_args}"
    # shellcheck disable=SC2086
    docker build ${extra_args} ${tag_flags} -f "${REPO_ROOT}/${dockerfile}" "${REPO_ROOT}"
}

pids=()

start_build() {
    local dockerfile="$1"
    local tags="$2"
    local extra_args="${3:-}"
    local primary_tag="${tags%% *}"
    local logfile="${REPO_ROOT}/.msh-sandbox/prebuild-logs/${primary_tag//[:\/]/_}.log"
    (
        build_one "${dockerfile}" "${tags}" "${extra_args}" >"${logfile}" 2>&1
    ) &
    # `|` separator survives tags that contain `:` (e.g. python:3.12-alpine).
    pids+=("$!|${primary_tag}|${logfile}")
}

# ── Parallel image builds ────────────────────────────────────────────────────
# Sanity-check that installDist output exists (the Dockerfiles will error
# otherwise but better to catch the cause upfront).
for dist in \
    "manager/service/build/install/manager" \
    "adapter/shepherd-adb/build/install/shepherd-adb" \
    "adapter/shepherd-farm/build/install/shepherd-farm" \
    "adapter/shepherd-cuttlefish/build/install/shepherd-cuttlefish"; do
    if [[ ! -x "${REPO_ROOT}/${dist}/bin/$(basename "${dist}")" ]]; then
        echo "[prebuild] ERROR: ${dist}/bin/$(basename "${dist}") missing — run gradle installDist first" >&2
        exit 1
    fi
done

start_build "tests/component/docker/Dockerfile.shepherd-adb" \
    "msh-shepherd-adb-integration:latest msh-shepherd-adb-smoke:latest"
start_build "tests/component/docker/Dockerfile.shepherd-farm" \
    "msh-shepherd-farm-integration:latest msh-shepherd-farm-smoke:latest"
start_build "tests/component/docker/Dockerfile.shepherd-cuttlefish" \
    "msh-shepherd-cuttlefish-integration:latest msh-shepherd-cuttlefish-smoke:latest"
start_build "tests/component/docker/Dockerfile" \
    "msh-manager-integration:latest msh-manager-smoke:latest"
start_build "deploy/Dockerfile.adb-server" \
    "msh-adb-server-integration:latest msh-adb-server-smoke:latest"

# cloud-orchestrator is a Go build — heaviest single image. Drops the
# --no-cache flag used by downstream scripts: BuildKit layer cache keeps git
# clone and go modules warm across runs, which is the whole point of moving
# this work to prebuild.
ORCHESTRATION_REF="${CLOUD_ANDROID_ORCHESTRATION_REF:-main}"
start_build "deploy/Dockerfile.cloud-orchestrator" \
    "msh-integration-cuttlefish-orchestrator:latest msh-smoke-cuttlefish-orchestrator:latest msh-cloud-orchestrator:latest" \
    "--build-arg CLOUD_ANDROID_ORCHESTRATION_REF=${ORCHESTRATION_REF}"

# ── Base-image pulls in parallel (warms the layer cache) ─────────────────────
# Every adapter/manager image FROM's these; pulling once up-front avoids a
# silent "downloading base image" wait inside the first `docker compose up`.
for base_image in "python:3.12-alpine" "eclipse-temurin:21-jre" "ubuntu:24.04"; do
    base_logfile="${REPO_ROOT}/.msh-sandbox/prebuild-logs/pull-${base_image//[:\/]/_}.log"
    (
        # `docker pull` is a no-op when the image already exists locally with
        # the right digest — safe and fast to call unconditionally.
        docker pull "${base_image}" >"${base_logfile}" 2>&1
    ) &
    pids+=("$!|pull ${base_image}|${base_logfile}")
done

# ── Config download in parallel with image builds ────────────────────────────
# The orchestrator config from Artifact Registry changes rarely but is NOT
# versioned in the URL — a stale cached copy can produce misleading e2e
# failures. Refresh on a 24h TTL (override with MSH_CONFIG_TTL_MINUTES, or
# force re-download with MSH_FORCE_CONFIG_REFRESH=1 / `run_tests.sh
# --refresh-cache`).
CONFIG_URL="${CUTTLEFISH_ORCHESTRATOR_CONFIG_URL:-https://artifactregistry.googleapis.com/download/v1/projects/android-cuttlefish-artifacts/locations/us/repositories/cloud-orchestrator-config/files/on-premise-single-server:unstable:conf.toml:download?alt=media}"
CONFIG_PATH="${PREBUILD_CACHE_DIR}/cloud-orchestrator.conf.toml"
CONFIG_LOG="${REPO_ROOT}/.msh-sandbox/prebuild-logs/config-download.log"
CONFIG_TTL_MINUTES="${MSH_CONFIG_TTL_MINUTES:-1440}"
export CONFIG_URL CONFIG_PATH CONFIG_LOG CONFIG_TTL_MINUTES
(
    # Reuse the cached config when it's fresh enough — cuts prebuild cold-start
    # noise (no Artifact Registry round-trip on every run).
    if [[ "${MSH_FORCE_CONFIG_REFRESH:-0}" != "1" ]] \
        && [[ -s "${CONFIG_PATH}" ]] \
        && [[ -n "$(find "${CONFIG_PATH}" -mmin "-${CONFIG_TTL_MINUTES}" -print -quit 2>/dev/null)" ]]; then
        printf 'reusing cached orchestrator config (TTL=%s min, file=%s)\n' \
            "${CONFIG_TTL_MINUTES}" "${CONFIG_PATH}" > "${CONFIG_LOG}"
        exit 0
    fi
    # Atomic write so a partial download doesn't poison the cache.
    tmp_path="${CONFIG_PATH}.partial"
    if curl -fsSL "${CONFIG_URL}" -o "${tmp_path}" 2>"${CONFIG_LOG}"; then
        mv "${tmp_path}" "${CONFIG_PATH}"
    else
        rm -f "${tmp_path}"
        exit 1
    fi
) &
pids+=("$!|config-download|${CONFIG_LOG}")

# ── Join all parallel workers ────────────────────────────────────────────────
failed=0
for entry in "${pids[@]}"; do
    pid="${entry%%|*}"
    rest="${entry#*|}"
    tag="${rest%%|*}"
    logfile="${rest#*|}"
    if wait "${pid}"; then
        log "OK  ${tag}"
    else
        log "FAIL ${tag} — tail of ${logfile}:"
        tail -n 20 "${logfile}" >&2 || true
        failed=$((failed + 1))
    fi
done

if (( failed > 0 )); then
    log "${failed} prebuild task(s) failed"
    exit 1
fi

# Emit the orchestrator config path for downstream consumers — the runner
# picks this up from stdout in the prebuild log and exports it as env.
log "cloud-orchestrator config cached at ${CONFIG_PATH}"
log "MSH_PREBUILT_ORCHESTRATOR_CONFIG=${CONFIG_PATH}"
log "all prebuild tasks completed successfully"
