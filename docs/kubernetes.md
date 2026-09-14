# Kubernetes

Two Helm charts live in `deploy/helm`:

| Chart | Deploys |
|-------|---------|
| [marathon-shepherd](../deploy/helm/marathon-shepherd/README.md) | The manager: one replica, `msh.yaml` in a Secret, SQLite on a volume or Postgres |
| [shepherd-adapter](../deploy/helm/shepherd-adapter/README.md) | One adapter (`shepherd-adb`, `shepherd-farm` or `shepherd-cuttlefish`) next to its devices |

Adapters often run on machines that are not Kubernetes nodes. Then only the manager goes into the
cluster, and its `msh.yaml` points at the adapters' addresses.

## A cluster with ingress

```bash
helm install shepherd deploy/helm/marathon-shepherd \
  --namespace shepherd --create-namespace \
  --set-file config.content=./msh.yaml \
  --set ingress.enabled=true --set ingress.host=shepherd.example.com
```

Set `auth.publicUrl` in `msh.yaml` to the ingress address when people sign in through OIDC, and
terminate TLS at the ingress.

## A small cluster without a load balancer

On k3s with servicelb and traefik turned off, or any cluster where nothing hands out addresses on
the local network, run the manager and the adapter on the host network of the node the phones are
plugged into. Clients keep using `http://<node>:6037`, and leased devices are reached on proxy
ports of the node's address.

Keep secrets out of the values files: create them first.

```bash
kubectl create namespace marathon-shepherd

adapter_secret="$(openssl rand -hex 32)"
kubectl -n marathon-shepherd create secret generic shepherd-adb \
  --from-literal=adapter-secret="$adapter_secret"

cat > msh.yaml <<EOF
providers:
  - name: device-rack-1
    url: "http://127.0.0.1:7037"
    accessHost: "<node address>"
    secret: "$adapter_secret"
EOF
kubectl -n marathon-shepherd create secret generic marathon-shepherd-config --from-file=msh.yaml
rm msh.yaml; unset adapter_secret
```

The adapter, `adb.values.yaml`:

```yaml
fullnameOverride: shepherd-adb
nodeSelector:
  kubernetes.io/hostname: <node>
hostNetwork: true
usb:
  enabled: true
service:
  enabled: false
secret:
  existingSecret: shepherd-adb
env:
  ADB_PROXY_PORT_RANGE: "7600-7609"
  ADB_LEASES_PATH: /var/lib/msh/adb-leases.json
  # Only when something on the node already listens on 127.0.0.1:5037, such as its own adb
  # server or the adbd of a board that acts as an Android device. Write localhost, not
  # 127.0.0.1: adb starts a server by itself only for a socket it considers local.
  ANDROID_ADB_SERVER_PORT: "5038"
  ADB_SERVER_SOCKET: "tcp:localhost:5038"
```

The manager, `manager.values.yaml`:

```yaml
nodeSelector:
  kubernetes.io/hostname: <node>
hostNetwork: true
config:
  create: false
  existingSecret: marathon-shepherd-config
```

```bash
helm install shepherd-adb deploy/helm/shepherd-adapter -n marathon-shepherd -f adb.values.yaml
helm install marathon-shepherd deploy/helm/marathon-shepherd -n marathon-shepherd -f manager.values.yaml
kubectl -n marathon-shepherd exec deploy/marathon-shepherd -- cat /var/lib/msh/initial-admin-password
```

Open `http://<node>:6037/` and sign in as `admin` with that one-time password.

- Pods on the host network bind the node's ports: nothing else there may use 6037, 7037 or the
  proxy range. Both charts use the Recreate strategy, so an upgrade stops the old pod before the
  new one starts, and takes the service down for a few seconds.
- `usb.enabled` runs the adapter privileged, with the node's `/dev/bus/usb` mounted.
- The volumes use the cluster's default storage class. With a replicated class such as Longhorn,
  the manager's data survives the loss of a node even though the pod is pinned to one.

## Moving from Docker Compose

1. Stop the Compose project on the host: `docker compose down` keeps its volumes, so you can go back.
2. Install the charts as above. The adapter gets a new secret from the Kubernetes Secret, so the old
   one can be thrown away.
3. To keep sessions, API clients and the audit log, move the old `msh.db` into the manager's
   volume: scale the manager to zero (`kubectl -n marathon-shepherd scale deploy/marathon-shepherd
   --replicas=0`), copy the file in through a pod that mounts its claim, and scale back to one.
   A manager with no history needs none of this.

## Delivery from Jenkins

The Jenkinsfile delivers master when its agent can use a Docker daemon:

1. **Publish images** builds `marathon-shepherd` and `shepherd-adb` from the checkout and pushes
   `:sha-<commit>` and `:edge` to `MSH_IMAGE_REGISTRY` (default `ghcr.io/rus-artur4ik`). It logs in
   with the Jenkins credentials `MSH_REGISTRY_CREDENTIALS` (default `ghcr-push`, of the kind
   *Username with password*: a GitHub user and a classic token with `write:packages`). Without them
   the delivery stages are skipped and the log says so.
2. **Deploy** upgrades the releases installed in `MSH_DEPLOY_NAMESPACE` (default
   `marathon-shepherd`) with `helm upgrade --reset-then-reuse-values --set image.tag=sha-<commit>`,
   using the kubeconfig at `MSH_KUBECONFIG_HOST_PATH` on the Docker host (default
   `/etc/rancher/k3s/k3s.yaml`). Releases keep the values they were installed with; one that is not
   installed is skipped, so make the first installation by hand.

Images are built for `MSH_IMAGE_PLATFORMS`, by default `linux/arm64`, what an arm64 agent builds
natively. A list such as `linux/arm64,linux/amd64` needs QEMU on the Docker host, for instance
`docker run --privileged --rm tonistiigi/binfmt --install amd64`. Release images, tagged `X.Y.Z`
from `vX.Y.Z` git tags, keep coming from GitHub Actions for both architectures.

The delivery scripts, `deploy/ci/publish-images.sh` and `deploy/ci/deploy-helm.sh`, run Docker CLI
and Helm in containers, so the agent only needs the Docker socket. On an agent that is itself a
container they reach the workspace through the agent's volumes.
