# Marathon Shepherd — observability

Prometheus + Grafana assets for the manager (`:6037`) and adapters (`:7037`, one
per device host). Both processes expose an unauthenticated Prometheus
`/metrics` endpoint; nothing here talks to the manager/adapter control APIs or
requires the `secret` configured in `msh.yaml`.

## Files

| File | What it is |
|---|---|
| `grafana/marathon-shepherd.json` | Importable Grafana dashboard (schemaVersion 39, uid `marathon-shepherd`). |
| `prometheus/alerts.yml` | Prometheus alerting rules, group `marathon-shepherd`. |
| `prometheus/scrape-config.example.yml` | Example `scrape_configs:` snippet for the manager and adapters. |

## Importing the dashboard

The dashboard uses two template variables, so nothing needs hand-editing before import:

- `datasource` — a Prometheus datasource picker (type `datasource`, query `prometheus`). Every panel and query references it as `${datasource}`.
- `provider` — a multi-select list of providers, populated from `label_values(msh_provider_up, provider)`, with "All" included. Panels whose metrics carry a `provider` label filter on `provider=~"$provider"`.

**Option A — UI import**

1. Grafana → Dashboards → New → Import.
2. Upload `grafana/marathon-shepherd.json` (or paste its contents).
3. When prompted, select the Prometheus datasource that scrapes the manager and adapters (see below). Grafana binds this to the `datasource` variable — the dashboard's panels don't hardcode a datasource UID.
4. Once opened, use the `provider` variable at the top to narrow to specific providers.

**Option B — provisioning**

Drop the file into your dashboard provisioning path (referenced from a `type: file` provider in Grafana's `dashboards.yaml`), e.g.:

```yaml
apiVersion: 1
providers:
  - name: marathon-shepherd
    type: file
    folder: Marathon Shepherd
    options:
      path: /etc/grafana/provisioning/dashboards/marathon-shepherd
```

Copy `grafana/marathon-shepherd.json` into that path. The `datasource` variable still resolves per-viewer/per-org at load time, so no UID substitution is needed as long as a Prometheus datasource is provisioned in the same Grafana instance.

## Loading the alerting rules

`prometheus/alerts.yml` is a standard `groups:` document, usable two ways:

**Option A — plain Prometheus**

Add the file's path to `rule_files:` in `prometheus.yml`:

```yaml
rule_files:
  - /etc/prometheus/rules/marathon-shepherd/alerts.yml
```

Then reload Prometheus (`SIGHUP`, `POST /-/reload`, or a restart).

**Option B — Prometheus Operator (`PrometheusRule` CRD)**

The file's top-level `groups:` list is exactly the shape of a `PrometheusRule`'s `spec`, so it pastes in unchanged:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: marathon-shepherd
  labels:
    release: prometheus   # match your Prometheus Operator's ruleSelector
spec:
  groups:
    - name: marathon-shepherd
      rules:
        # ...copy the "rules:" list from prometheus/alerts.yml here...
```

Two rules — `ShepherdManagerDown` and `ShepherdAdapterScrapeDown` — key off the standard `up{job=...}` series (`marathon-shepherd-manager` / `marathon-shepherd-adapters`). Keep those job names in sync with whatever you actually name the scrape jobs; `scrape-config.example.yml` uses the same names so the rules work unmodified if you start from it.

## Scrape configuration

`prometheus/scrape-config.example.yml` has two jobs to copy into `scrape_configs:`: the manager on `:6037` and a static list of adapters on `:7037`, both scraped every 15s at `/metrics`. Read the comments in that file before using it in a shared environment — `/metrics` is unauthenticated on both processes and is meant to be scraped only from Prometheus (or an agent) on the same private network as the fleet.

## Metric reference

This is the authoritative list of metric names the dashboard and alerts are built against.

### Manager (`:6037/metrics`)

| Metric | Type | Labels | Notes |
|---|---|---|---|
| `msh_build_info` | gauge | `version`, `component="manager"` | Value is always `1` |
| `msh_sessions` | gauge | `status="pending"\|"ready"` | |
| `msh_sessions_devices` | gauge | — | Devices held by READY sessions |
| `msh_sessions_accepted_total` | counter | `device_type="physical"\|"emulator"\|"any"` | |
| `msh_sessions_rejected_total` | counter | `reason` | e.g. `no_matching_devices` |
| `msh_sessions_finished_total` | counter | `status="released"\|"expired"\|"failed"` | |
| `msh_sessions_queue_wait_seconds_{bucket,count,sum,max}` | histogram | — | Creation to allocation |
| `msh_sessions_lifetime_seconds_{bucket,count,sum,max}` | histogram | `status` | Creation to terminal status |
| `msh_sessions_stored` | gauge | `status="pending"\|"ready"\|"failed"\|"released"\|"expired"` | Sessions in the database; finished ones stay for `sessions.retentionDays`. Read from the database every minute, so it survives a restart, unlike the counters |
| `msh_sessions_last_request_seconds` | gauge | — | Unix time the newest session in the database was requested; `0` when there is none |
| `msh_devices_allocated_total` | counter | — | |
| `msh_provider_up` | gauge | `provider` | `0`/`1` |
| `msh_provider_devices` | gauge | `provider`, `state="available"\|"busy"\|"total"` | |
| `msh_devices` | gauge | `provider`, `state="available"\|"busy"\|"offline"`, `device_type` | Per-device state from adapters that list devices (shepherd-adb). |
| `msh_leases_reclaimed_total` | counter | `provider` | Orphaned adapter leases released by reconciliation. |
| `msh_events_subscribers` | gauge | | Clients connected to `GET /api/v1/events`. |
| `msh_adapter_requests_seconds_{bucket,count,sum,max}` | histogram | `provider`, `operation="health"\|"status"\|"acquire"\|"release"`, `outcome="success"\|"unavailable"\|"http_error"\|"error"\|"timeout"\|"cancelled"` | Manager → adapter HTTP calls |
| `msh_fleet_last_poll_seconds` | gauge | — | Unix time of the last completed fleet poll |
| `ktor_http_server_requests_seconds_{bucket,count,sum,max}` | histogram | `method`, `route`, `status`, `throwable` | Manager's own HTTP server |
| standard JVM/process metrics | gauge/histogram | — | `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage`, `jvm_threads_live_threads`, ... |

### Each adapter (`:7037/metrics`)

| Metric | Type | Labels | Notes |
|---|---|---|---|
| `msh_build_info` | gauge | `version`, `component="adapter"`, `adapter_type` | |
| `msh_adapter_pool_devices` | gauge | `state="available"\|"busy"\|"total"` | From the last `/status` answer |
| `msh_adapter_last_status_seconds` | gauge | — | Unix time of the last `/status` answer |
| `msh_adapter_acquire_total` | counter | `outcome="full"\|"partial"\|"empty"\|"error"` | |
| `msh_adapter_acquired_devices_total` | counter | — | |
| `msh_adapter_acquire_duration_seconds_{bucket,count,sum,max}` | histogram | — | |
| `msh_adapter_release_total` | counter | `outcome="success"\|"failure"` | |
| — | — | — | Same `ktor_http_server_requests_seconds_*` and JVM/process metrics as the manager |

Note the asymmetry: `provider` is a manager-side concept (the name from `msh.yaml`), so adapter-scraped metrics (`msh_adapter_pool_devices`, `msh_adapter_acquire_*`, `msh_adapter_release_*`, `msh_adapter_last_status_seconds`) don't carry it — the dashboard groups those by Prometheus's own `instance` label instead. Only `msh_provider_up`, `msh_provider_devices`, and `msh_adapter_requests_seconds_*` (all manager-side) carry `provider`, and only those panels apply the `provider` template variable.

## Validating changes

```bash
python3 -c "import json,sys; json.load(open(sys.argv[1]))" deploy/observability/grafana/marathon-shepherd.json
ruby -ryaml -e 'YAML.load_file(ARGV[0])' deploy/observability/prometheus/alerts.yml
ruby -ryaml -e 'YAML.load_file(ARGV[0])' deploy/observability/prometheus/scrape-config.example.yml
```

If `promtool` is available, also run `promtool check rules deploy/observability/prometheus/alerts.yml`.
