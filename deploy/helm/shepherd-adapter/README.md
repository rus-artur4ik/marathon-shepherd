# shepherd-adapter

Deploys one Marathon Shepherd adapter next to the devices it serves: `shepherd-adb` on the node
the phones are plugged into, `shepherd-farm` or `shepherd-cuttlefish` next to their backends. The
manager has its own chart, [marathon-shepherd](../marathon-shepherd/README.md); register the
adapter there as a provider. [docs/kubernetes.md](../../../docs/kubernetes.md) walks through a
whole installation.

## Phones plugged into a node

```bash
kubectl create namespace marathon-shepherd
kubectl -n marathon-shepherd create secret generic shepherd-adb \
  --from-literal=adapter-secret="$(openssl rand -hex 32)"

helm install shepherd-adb deploy/helm/shepherd-adapter --namespace marathon-shepherd \
  --set fullnameOverride=shepherd-adb \
  --set nodeSelector."kubernetes\.io/hostname"=<node> \
  --set hostNetwork=true --set usb.enabled=true --set service.enabled=false \
  --set secret.existingSecret=shepherd-adb \
  --set env.ADB_PROXY_PORT_RANGE=7600-7609 \
  --set env.ADB_LEASES_PATH=/var/lib/msh/adb-leases.json
```

The pod shares the node's network, so the manager reaches it at `http://<node>:7037` and clients
reach leased devices on the proxy ports of the node's address:

```yaml
providers:
  - name: device-rack-1
    url: "http://<node address>:7037"
    accessHost: "<node address>"
    secret: "<the adapter-secret value>"
```

The adapter starts an adb server in the pod. When something on the node already listens on
`127.0.0.1:5037` (the node's own adb server, or an adbd on boards that act as Android devices), give
the pod's server another port: `--set env.ANDROID_ADB_SERVER_PORT=5038 --set
env.ADB_SERVER_SOCKET=tcp:127.0.0.1:5038`.

## Values

| Value | Default | What it does |
|-------|---------|--------------|
| `image.repository`, `image.tag` | `shepherd-adb` on GHCR, chart `appVersion` | Which adapter to run |
| `port` | `7037` | `ADAPTER_PORT`, where the manager calls the adapter |
| `secret.value` / `.existingSecret`, `.key` | `""`, `adapter-secret` | `ADAPTER_SECRET`, the provider's `secret` in `msh.yaml` |
| `env`, `extraEnv` | none | Adapter settings, such as `ADB_PROXY_PORT_RANGE` |
| `hostNetwork` | `false` | Share the node's network, so the ports the adapter hands out work on the node's address |
| `usb.enabled`, `.hostPath` | `false`, `/dev/bus/usb` | Run privileged with the node's USB bus mounted |
| `persistence.enabled`, `.size`, `.mountPath` | `true`, `256Mi`, `/var/lib/msh` | Adapter state such as the lease file |
| `service.enabled`, `.type` | `true`, `ClusterIP` | A Service for managers inside the cluster; not needed with `hostNetwork` |
| `resources`, `nodeSelector`, `tolerations`, `affinity` | small requests | Usual pod knobs; pin the pod to the device node |
