#!/usr/bin/env bash

CLOUD_ORCHESTRATOR_CONFIG_URL_DEFAULT="https://artifactregistry.googleapis.com/download/v1/projects/android-cuttlefish-artifacts/locations/us/repositories/cloud-orchestrator-config/files/on-premise-single-server:unstable:conf.toml:download?alt=media"
CLOUD_ORCHESTRATOR_IMAGE_DEFAULT="msh-cloud-orchestrator:latest"
CLOUD_ANDROID_ORCHESTRATION_REF_DEFAULT="main"

ensure_cloud_orchestrator_defaults() {
    export CUTTLEFISH_ORCHESTRATOR_CONFIG_URL="${CUTTLEFISH_ORCHESTRATOR_CONFIG_URL:-${CLOUD_ORCHESTRATOR_CONFIG_URL_DEFAULT}}"
    export CUTTLEFISH_ORCHESTRATOR_IMAGE="${CUTTLEFISH_ORCHESTRATOR_IMAGE:-${CLOUD_ORCHESTRATOR_IMAGE_DEFAULT}}"
    export CLOUD_ANDROID_ORCHESTRATION_REF="${CLOUD_ANDROID_ORCHESTRATION_REF:-${CLOUD_ANDROID_ORCHESTRATION_REF_DEFAULT}}"
}

download_cloud_orchestrator_config() {
    local workspace="$1"
    ensure_cloud_orchestrator_defaults
    # If the runner pre-downloaded the config during [build] docker images,
    # reuse that file instead of re-fetching from the Artifact Registry.
    if [[ -n "${MSH_PREBUILT_ORCHESTRATOR_CONFIG:-}" && -s "${MSH_PREBUILT_ORCHESTRATOR_CONFIG}" ]]; then
        export CUTTLEFISH_ORCHESTRATOR_CONFIG_PATH="${MSH_PREBUILT_ORCHESTRATOR_CONFIG}"
        return 0
    fi
    export CUTTLEFISH_ORCHESTRATOR_CONFIG_PATH="${CUTTLEFISH_ORCHESTRATOR_CONFIG_PATH:-${workspace}/cloud-orchestrator.conf.toml}"
    curl -fsSL "${CUTTLEFISH_ORCHESTRATOR_CONFIG_URL}" -o "${CUTTLEFISH_ORCHESTRATOR_CONFIG_PATH}"
}

build_cloud_orchestrator_image() {
    ensure_cloud_orchestrator_defaults
    # When prebuild already produced the image and tagged it with
    # msh-cloud-orchestrator:latest, skip the slow --no-cache rebuild.
    if [[ "${MSH_SKIP_DOCKER_BUILD:-0}" == "1" ]] && \
       docker image inspect "${CUTTLEFISH_ORCHESTRATOR_IMAGE}" >/dev/null 2>&1; then
        return 0
    fi
    docker build --no-cache \
        -f "${REPO_ROOT}/deploy/Dockerfile.cloud-orchestrator" \
        --build-arg "CLOUD_ANDROID_ORCHESTRATION_REF=${CLOUD_ANDROID_ORCHESTRATION_REF}" \
        -t "${CUTTLEFISH_ORCHESTRATOR_IMAGE}" \
        "${REPO_ROOT}"
}

run_cloud_orchestrator_container() {
    local container_name="$1"
    local host_port="$2"
    ensure_cloud_orchestrator_defaults
    docker rm -f "${container_name}" >/dev/null 2>&1 || true
    docker run -d \
        --name "${container_name}" \
        -p "127.0.0.1:${host_port}:8080" \
        -e CONFIG_FILE="/conf.toml" \
        -v "${CUTTLEFISH_ORCHESTRATOR_CONFIG_PATH}:/conf.toml:ro" \
        -v /var/run/docker.sock:/var/run/docker.sock \
        "${CUTTLEFISH_ORCHESTRATOR_IMAGE}" >/dev/null
}

cleanup_cloud_orchestrator_image() {
    # No-op: the cloud-orchestrator image is a heavy Go build (~30-60s).
    # Runner-level prebuild stage caches it across runs; removing here would
    # force the next stage (or next full run) to rebuild from scratch.
    # Kept as a function so callers that expect it don't break.
    :
}
