#!/usr/bin/env bash
# Upgrades the Helm releases of an installation to the images the pipeline has just pushed.
# Each release keeps the values it was installed with (docs/kubernetes.md); only the image tag
# changes. A release that is not installed is skipped: the first installation is made by hand.
#
# Needs a Docker daemon; Helm runs in a container. Environment:
#   MSH_IMAGE_TAG             e.g. sha-1a2b3c4
#   MSH_DEPLOY_NAMESPACE      namespace of the releases
#   MSH_KUBECONFIG_HOST_PATH  kubeconfig on the Docker host, e.g. /etc/rancher/k3s/k3s.yaml
#   MSH_HELM_RELEASES         release=chart pairs, default: the manager and shepherd-adb
set -euo pipefail

: "${MSH_IMAGE_TAG:?set MSH_IMAGE_TAG}"
: "${MSH_DEPLOY_NAMESPACE:?set MSH_DEPLOY_NAMESPACE}"
: "${MSH_KUBECONFIG_HOST_PATH:?set MSH_KUBECONFIG_HOST_PATH}"
releases="${MSH_HELM_RELEASES:-marathon-shepherd=deploy/helm/marathon-shepherd shepherd-adb=deploy/helm/shepherd-adapter}"
helm_image="${MSH_HELM_IMAGE:-alpine/helm:3.19.0}"

if [ -f /.dockerenv ]; then
  workspace=(--volumes-from "$(hostname)")
else
  workspace=(-v "$PWD:$PWD")
fi

helm() {
  docker run --rm --network host "${workspace[@]}" -w "$PWD" \
    -v "$MSH_KUBECONFIG_HOST_PATH:/tmp/kubeconfig:ro" -e KUBECONFIG=/tmp/kubeconfig \
    "$helm_image" "$@"
}

# Listing fails loudly when the cluster cannot be reached, instead of looking like "nothing installed".
installed="$(helm list --namespace "$MSH_DEPLOY_NAMESPACE" --short)"
upgraded=0
for entry in $releases; do
  release="${entry%%=*}"
  chart="${entry#*=}"
  if ! printf '%s\n' "$installed" | grep -qx "$release"; then
    echo "Release $release is not installed in namespace $MSH_DEPLOY_NAMESPACE; skipping it."
    continue
  fi
  echo "Upgrading $release to image tag $MSH_IMAGE_TAG"
  helm upgrade "$release" "$chart" --namespace "$MSH_DEPLOY_NAMESPACE" \
    --reset-then-reuse-values --set image.tag="$MSH_IMAGE_TAG" --wait --timeout 10m
  upgraded=$((upgraded + 1))
done
echo "Upgraded $upgraded release(s) in $MSH_DEPLOY_NAMESPACE."
