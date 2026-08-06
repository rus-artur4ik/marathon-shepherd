#!/usr/bin/env bash
#
# Builds and pushes the four Marathon Shepherd container images.
#
# Nothing here is tied to a particular account: point it at your own registry
# namespace. Defaults are read from the environment so CI can drive it without flags.
#
#   MSH_REGISTRY_NAMESPACE   registry/namespace to publish under (required)
#   MSH_IMAGE_TAG            tag to build and push        (default: the project version)
#
# Examples:
#
#   MSH_REGISTRY_NAMESPACE=ghcr.io/acme ./scripts/publish_dockerhub.sh --tag 0.1.0
#   ./scripts/publish_dockerhub.sh --namespace myuser --tag 0.1.0 --latest --dry-run
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

NAMESPACE="${MSH_REGISTRY_NAMESPACE:-}"
# Default tag tracks the single source of truth in gradle.properties.
DEFAULT_TAG="$(sed -n 's/^shepherdVersion=//p' "${REPO_ROOT}/gradle.properties" | tr -d '[:space:]')"
TAG="${MSH_IMAGE_TAG:-${DEFAULT_TAG:-latest}}"
ALSO_LATEST=false
DRY_RUN=false
ASSUME_YES=false

readonly IMAGES=(
  "deploy/Dockerfile:marathon-shepherd"
  "deploy/Dockerfile.shepherd-adb:shepherd-adb"
  "deploy/Dockerfile.shepherd-farm:shepherd-farm"
  "deploy/Dockerfile.shepherd-cuttlefish:shepherd-cuttlefish"
)

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --namespace <ns>  Registry namespace, e.g. 'myuser' or 'ghcr.io/acme'.
                    Overrides MSH_REGISTRY_NAMESPACE.
  --tag <tag>       Image tag to build and push (default: ${TAG}).
  --latest          Additionally tag and push ':latest'.
  --dry-run         Print the docker commands without running them.
  --yes             Skip the interactive confirmation (for CI).
  -h, --help        Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace) NAMESPACE="${2:?--namespace needs a value}"; shift 2 ;;
    --tag)       TAG="${2:?--tag needs a value}"; shift 2 ;;
    --latest)    ALSO_LATEST=true; shift ;;
    --dry-run)   DRY_RUN=true; shift ;;
    --yes)       ASSUME_YES=true; shift ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "${NAMESPACE}" ]]; then
  echo "No registry namespace set. Pass --namespace or set MSH_REGISTRY_NAMESPACE." >&2
  echo "Example: MSH_REGISTRY_NAMESPACE=ghcr.io/acme $(basename "$0") --tag ${TAG}" >&2
  exit 2
fi

run() {
  if [[ "${DRY_RUN}" == true ]]; then
    printf '  [dry-run] %s\n' "$*"
  else
    "$@"
  fi
}

if [[ "${DRY_RUN}" != true ]]; then
  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is not available." >&2
    exit 1
  fi
  if [[ "${ASSUME_YES}" != true ]]; then
    read -r -p "Build and push ${#IMAGES[@]} images to '${NAMESPACE}' with tag '${TAG}'. Type 'yes' to continue: " confirmation
    if [[ "${confirmation}" != "yes" ]]; then
      echo "Aborted."
      exit 1
    fi
  fi
fi

cd "${REPO_ROOT}"

echo "Building images for ${NAMESPACE} (tag ${TAG})..."
for entry in "${IMAGES[@]}"; do
  dockerfile="${entry%%:*}"
  image="${entry##*:}"
  run docker build -f "${dockerfile}" -t "${NAMESPACE}/${image}:${TAG}" .
  if [[ "${ALSO_LATEST}" == true ]]; then
    run docker tag "${NAMESPACE}/${image}:${TAG}" "${NAMESPACE}/${image}:latest"
  fi
done

echo "Pushing images..."
for entry in "${IMAGES[@]}"; do
  image="${entry##*:}"
  run docker push "${NAMESPACE}/${image}:${TAG}"
  if [[ "${ALSO_LATEST}" == true ]]; then
    run docker push "${NAMESPACE}/${image}:latest"
  fi
done

echo "Published ${#IMAGES[@]} images to ${NAMESPACE} with tag ${TAG}."
