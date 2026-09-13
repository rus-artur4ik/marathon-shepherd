# marathon-shepherd (Helm chart)

Deploys the Marathon Shepherd manager. Adapters are not part of the chart: they run on the
machines that hold the devices — a rack host with USB phones, an emulator farm, a Cuttlefish
host — which is rarely a Kubernetes node.

```bash
helm install shepherd deploy/helm/marathon-shepherd \
  --namespace shepherd --create-namespace \
  --set-file config.content=./msh.yaml
```

The chart runs exactly one manager: the session state, the queue and the leases assume a single
writer, and the manager takes a lock on its database at startup. `replicas` is not a value, and
the deployment uses the `Recreate` strategy so a rollout never runs two at once.

## First steps after installing

```bash
kubectl -n shepherd port-forward svc/shepherd-marathon-shepherd 6037
# The first admin's one-time password, printed once at startup and kept in the data volume:
kubectl -n shepherd exec deploy/shepherd-marathon-shepherd -- cat /var/lib/msh/initial-admin-password
export MSH_URL=http://localhost:6037
mshctl passwd --username admin          # choose your own password
mshctl login --username admin
mshctl users create --username dana --role user
```

Set `adminPassword.existingSecret` to choose the first password yourself. People can also sign in
through LDAP or OIDC: configure `auth` in `config.content`, set `auth.publicUrl` to the ingress
address, and pass secrets through `extraEnv` (see docs/authentication.md).

## Values

| Value | Default | What it does |
|-------|---------|--------------|
| `image.repository`, `image.tag` | GHCR image, chart `appVersion` | Manager image |
| `config.create`, `config.content` | `true`, one example provider | `msh.yaml`, stored in a Secret because it holds adapter secrets |
| `config.existingSecret` | `""` | Use your own Secret for `msh.yaml` instead |
| `adminPassword.value` / `.existingSecret` | `""` | `MSH_ADMIN_PASSWORD`, the first password of `admin`; without it a one-time password is generated |
| `adminToken.value` / `.existingSecret` | `""` | `MSH_ADMIN_TOKEN`, an optional static admin API key for automation |
| `database.url` / `.existingSecret` | `""` | `MSH_DB_URL`, a `jdbc:postgresql://...` URL; empty keeps SQLite in the data volume |
| `persistence.enabled`, `.size` | `true`, `1Gi` | The data directory: SQLite state and the generated admin password |
| `service.type`, `.port` | `ClusterIP`, `6037` | How the API is exposed inside the cluster |
| `ingress.*` | disabled | Ingress for the API. Terminate TLS there: the manager speaks plain HTTP and every call carries an API key |
| `serviceMonitor.enabled` | `false` | Prometheus Operator scrape of `/metrics` |
| `resources`, `nodeSelector`, `tolerations`, `affinity`, `extraEnv` | sensible defaults | Usual pod knobs |

Alert rules are not templated; load `deploy/observability/prometheus/alerts.yml` into your
Prometheus (or a `PrometheusRule`) alongside the dashboard in `deploy/observability/grafana`.

## Postgres

SQLite in a `ReadWriteOnce` volume is enough for one manager, and it is the default. Point
`database.url` at Postgres when you would rather back up and fail over with your database:

```bash
helm upgrade shepherd deploy/helm/marathon-shepherd \
  --set database.existingSecret=shepherd-db --set database.key=url \
  --set persistence.size=1Gi
```

The data directory is still used for the generated admin password, so keep persistence on unless you
provision `adminPassword` yourself. Postgres does not enable a second replica: the manager takes a
`pg_try_advisory_lock` at startup and a second one exits.
