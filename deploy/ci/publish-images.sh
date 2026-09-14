#!/usr/bin/env bash
# Builds the manager and shepherd-adb images from this checkout and pushes them to
# $MSH_IMAGE_REGISTRY as :$MSH_IMAGE_TAG and :edge. The Jenkinsfile runs it on master.
#
# Needs a Docker daemon. The build runs in a docker:cli container, so an agent with an old Docker
# CLI and no buildx works too. Environment:
#   MSH_IMAGE_REGISTRY    e.g. ghcr.io/rus-artur4ik
#   MSH_IMAGE_TAG         e.g. sha-1a2b3c4
#   REGISTRY_USER, REGISTRY_TOKEN
#   MSH_IMAGE_PLATFORMS   buildx platforms, default: the daemon's own. A list such as
#                         linux/arm64,linux/amd64 needs QEMU on the Docker host.
set -euo pipefail

: "${MSH_IMAGE_REGISTRY:?set MSH_IMAGE_REGISTRY}"
: "${MSH_IMAGE_TAG:?set MSH_IMAGE_TAG}"
: "${REGISTRY_USER:?set REGISTRY_USER}"
: "${REGISTRY_TOKEN:?set REGISTRY_TOKEN}"
docker_cli_image="${MSH_DOCKER_CLI_IMAGE:-docker:28-cli}"
platforms="${MSH_IMAGE_PLATFORMS:-linux/$(docker version --format '{{.Server.Arch}}')}"

# In a containerised agent the workspace lives in the agent's own volumes: lend them to the
# build container so it sees this checkout at the same path.
if [ -f /.dockerenv ]; then
  workspace=(--volumes-from "$(hostname)")
else
  workspace=(-v "$PWD:$PWD" -v /var/run/docker.sock:/var/run/docker.sock)
fi

docker run --rm "${workspace[@]}" -w "$PWD" \
  -e REGISTRY_USER -e REGISTRY_TOKEN -e MSH_IMAGE_REGISTRY -e MSH_IMAGE_TAG \
  -e PLATFORMS="$platforms" \
  "$docker_cli_image" sh -euc '
    export DOCKER_CONFIG="$(mktemp -d)"
    printf "%s" "$REGISTRY_TOKEN" | docker login "${MSH_IMAGE_REGISTRY%%/*}" --username "$REGISTRY_USER" --password-stdin
    builder=""
    case "$PLATFORMS" in
      *,*)
        builder="msh-publish-$$"
        docker buildx create --name "$builder" --driver docker-container >/dev/null
        trap "docker buildx rm \"$builder\" >/dev/null 2>&1 || true" EXIT
        ;;
    esac
    for spec in marathon-shepherd:deploy/Dockerfile shepherd-adb:deploy/Dockerfile.shepherd-adb; do
      image="$MSH_IMAGE_REGISTRY/${spec%%:*}"
      echo "Building $image:$MSH_IMAGE_TAG for $PLATFORMS"
      docker buildx build ${builder:+--builder "$builder"} --platform "$PLATFORMS" --file "${spec#*:}" \
        --tag "$image:$MSH_IMAGE_TAG" --tag "$image:edge" --push .
    done
  '
