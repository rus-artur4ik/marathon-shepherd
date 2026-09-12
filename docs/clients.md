# Connect your client

Everything that uses devices — CI jobs, developers, scripts and bots — talks to the manager's
HTTP API on port `6037`. This page covers getting an API key, the session lifecycle, and the
four ways to call the API: `mshctl`, the Kotlin client, the Python client and plain HTTP.
The full reference is the OpenAPI document the manager serves at `/openapi.yaml`, browsable
at `/docs`.

## API keys

Every `/api/v1/...` call carries `Authorization: Bearer <key>`. `/live`, `/ready`,
`/health`, `/metrics` and the API docs stay public.

On first start the manager creates an admin key, prints it once and stores it in
`<dataDir>/initial-admin-token`; set `MSH_ADMIN_TOKEN` to provision a known admin token
instead. With the admin key, create one client per consumer:

```bash
export MSH_URL=http://manager.internal:6037
export MSH_TOKEN=<admin key>
mshctl clients create --name android-ci --role user --max-devices 8 --max-lifetime 7200
```

The new key is printed once; only its prefix can be seen later. `mshctl clients rotate` issues a
replacement and `mshctl clients revoke` disables a client (`--release-sessions` also frees its
devices).

| Role       | Can                                                                     |
|------------|-------------------------------------------------------------------------|
| `admin`    | everything: clients, device maintenance, providers, configuration, every session |
| `user`     | create and manage its own sessions; read devices, providers and events  |
| `viewer`   | read devices, sessions, providers and events                            |
| `provider` | register an adapter (`POST /api/v1/providers/register`); nothing else   |

Quotas limit a client's concurrent devices (held plus queued), session lifetime and queue
priority. Unset limits fall back to the `quotas` section of `msh.yaml`. `GET /api/v1/me`
(`mshctl whoami`) shows a key's limits and current usage.

## Session lifecycle

1. **Create** — `POST /api/v1/sessions`. The answer is `201` with status `READY` (devices
   allocated) or `PENDING` (queued, with `queuePosition`).
2. **Wait** — while `PENDING`, long-poll `POST /api/v1/sessions/{id}/wait` with
   `{"timeoutSeconds": 30}` (the maximum). A queued session that nobody waits on for 90
   seconds is dropped, so a client that stops polling does not hold a place in the queue.
3. **Use** — a `READY` session lists `adbServers`; run `adb -H <host> -P <port> devices` against
   each. Providers that report individual devices also fill `devices` with global ids.
4. **Keep alive** — a session with `idleTimeoutSeconds` is released after that long without a
   `POST /heartbeat`. `POST /extend` with `{"ttlSeconds": N}` moves the expiry to N seconds
   from now, within the client's lifetime quota (and only on providers that can renew leases).
5. **Release** — `DELETE /api/v1/sessions/{id}`. Sessions also end when `ttlSeconds` runs out
   (`EXPIRED`) or allocation fails (`FAILED`).

What a session can ask for:

| Field                | Meaning                                                               |
|----------------------|-----------------------------------------------------------------------|
| `maxDevices`         | number of devices (default 1, or one per entry of `deviceIds`)        |
| `api`                | API levels: `34`, `>=33`, `<35`, `34+`, `33..35` or `33,34`; unset = any |
| `deviceType`         | `physical` or `emulator`                                              |
| `deviceIds`          | only these devices, by global id `provider:serial` (see `mshctl devices`) |
| `labels`             | every device must carry these labels, e.g. `{"form": "tablet"}`       |
| `ttlSeconds`         | lifetime, default 3600                                                |
| `idleTimeoutSeconds` | release after this long without a heartbeat                           |
| `name`, `metadata`   | shown in listings and events, e.g. the CI job and build URL           |
| `priority`           | queue order under the `priority` scheduler, capped by the quota       |

## mshctl

```bash
mshctl whoami
mshctl devices --state available --api '>=34'
mshctl create --devices 2 --api 34 --name "nightly #42" --wait
mshctl list --mine
mshctl extend sess_abc123 --ttl 1800
mshctl release sess_abc123
mshctl events --type session.
```

Every command accepts `--manager`, `--token` and `--json`; `MSH_URL` and `MSH_TOKEN` supply
the defaults. `create --wait` stays in the queue until the devices are allocated and releases
the session on Ctrl+C. Admin commands: `devices maintenance <id> [--reason ...|--off]`,
`providers remove <name>`, `clients ...`, `audit`, `config` and `config reload`. Run
`mshctl <command> --help` for the options.

## Kotlin

`manager/client` implements the shared `ShepherdApi` interface over HTTP. It is not published
to a Maven repository yet; consume it through a composite build
(`includeBuild("../marathon-shepherd")` and `implementation("dev.shepherd:client")`).

```kotlin
ShepherdClient("http://manager.internal:6037", System.getenv("MSH_TOKEN")).use { shepherd ->
    runBlocking {
        val request = CreateSessionRequest(maxDevices = 2, api = ">=34", name = "nightly #42", idleTimeoutSeconds = 300)
        shepherd.withSession(request) { session ->
            session.adbServers.forEach { server -> println("adb -H ${server.host} -P ${server.port} devices") }
            runTests(session)
        }
    }
}
```

`withSession` waits through the queue, heartbeats while the block runs when
`idleTimeoutSeconds` is set, and always releases. `acquire` does the waiting part alone and
releases the session if it times out, fails or is cancelled. Failed calls throw
`ShepherdApiException` with the HTTP status and the manager's message. `events()` is a
`Flow` of manager events that reconnects on its own.

## Python

`clients/python` is a dependency-free client for Python 3.9+:

```bash
pip install ./clients/python
```

```python
from marathon_shepherd import ShepherdClient

client = ShepherdClient("http://manager.internal:6037")  # the key comes from MSH_TOKEN
with client.session(max_devices=2, api=">=34", name="nightly #42", idle_timeout_seconds=300) as session:
    for server in session.adb_servers:
        print(f"adb -H {server.host} -P {server.port} devices")
```

The context manager waits through the queue, heartbeats in the background when
`idle_timeout_seconds` is set, and releases on exit — including on exceptions and Ctrl+C.
Errors raise `ShepherdError` subclasses (`BadRequest`, `Unauthorized`, `Forbidden`, `NotFound`,
`Conflict`, `QuotaExceeded`, `Unavailable`), plus `QueueTimeout` and `SessionFailed` from the
helpers. A pytest plugin provides a `shepherd_session` fixture
configured from `MSH_URL`, `MSH_TOKEN`, `MSH_DEVICES`, `MSH_API` and `MSH_DEVICE_TYPE`.

## Plain HTTP

```bash
curl -sf -H "Authorization: Bearer $MSH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"maxDevices": 2, "api": ">=34", "ttlSeconds": 1800, "name": "manual run"}' \
  "$MSH_URL/api/v1/sessions"

curl -sf -X POST -H "Authorization: Bearer $MSH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"timeoutSeconds": 30}' "$MSH_URL/api/v1/sessions/sess_abc123/wait"

curl -sf -X DELETE -H "Authorization: Bearer $MSH_TOKEN" "$MSH_URL/api/v1/sessions/sess_abc123"
```

Errors are JSON `{"error": "..."}`:

| Status | Meaning                                                                         |
|--------|---------------------------------------------------------------------------------|
| 400    | invalid request, e.g. a malformed API selector or an unknown device id         |
| 401    | missing, unknown or revoked API key                                             |
| 403    | the key's role does not allow the call, or the session belongs to another client |
| 404    | no such session, device, client or provider                                     |
| 409    | the call conflicts with the current state, e.g. extending a released session   |
| 429    | the client's quota would be exceeded                                            |
| 503    | no registered device can ever satisfy the request, or the manager is not ready  |

## Events

`GET /api/v1/events` is a server-sent event stream of session, device, provider and lease
events. `types=session.,provider.` filters by type prefix, and a reconnecting client sends
`Last-Event-ID` to receive what it missed (the manager keeps the latest 1000 events).

```bash
curl -N -H "Authorization: Bearer $MSH_TOKEN" "$MSH_URL/api/v1/events?types=session."
```
