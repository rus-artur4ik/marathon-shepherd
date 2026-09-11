# marathon-shepherd for Python

A client for the Marathon Shepherd manager API. It needs Python 3.9 or later and nothing beyond the standard library. Use it to lease Android test devices from CI jobs, scripts and pytest suites.

## Install

```sh
pip install ./clients/python
```

## Quick start

```python
import subprocess
from marathon_shepherd import ShepherdClient

client = ShepherdClient()  # URL from $MSH_URL, API key from $MSH_TOKEN

with client.session(max_devices=2, api=">=34", name="nightly #42") as session:
    for server in session.adb_servers:
        subprocess.run(["adb", *server.adb_args(), "devices"], check=True)
```

`session()` requests the devices and waits in the queue until they are READY, for at most `queue_timeout` seconds (900 by default). It releases them when the block exits, whether the block succeeds, raises or is interrupted with Ctrl-C. If you pass `idle_timeout_seconds`, the manager also reclaims the devices when your process dies without releasing them, and a background thread sends heartbeats while the block runs.

`acquire()` takes the same arguments and returns the READY session without a `with` block. It still releases the session itself if the wait fails or is interrupted. Once it has returned, calling `release_session()` is up to you. To pass the URL and key directly, use `ShepherdClient(base_url, token, timeout)`.

## Using the adb endpoints

A READY session lists its adb server endpoints in `session.adb_servers`. Point adb at one with `-H` and `-P`:

```sh
adb -H 10.0.0.5 -P 5037 devices
adb -H 10.0.0.5 -P 5037 -s <serial> shell getprop ro.build.version.sdk
```

You can also export the endpoint. That reaches tools that start adb themselves, too:

```sh
export ANDROID_ADB_SERVER_ADDRESS=10.0.0.5 ANDROID_ADB_SERVER_PORT=5037
```

`server.adb_args()` and `server.env()` build both forms. For providers that report individual devices, `session.devices` lists each one with the `adb_server` that reaches it.

## Events

```python
for event in client.events(types="session.,provider."):
    print(event.id, event.type, event.data)
```

`events()` is a generator over the manager's server-sent event stream. When the connection drops, it reconnects with the last event id and the manager replays what was missed. To resume in a later process, store `event.id` and pass it back as `last_event_id`. A rejected key (401) or a missing role (403) raises instead of retrying.

## Errors

Every error response raises a `ShepherdError`. Its `status` is the HTTP status and its `message` is the manager's `error` text:

| Status | Exception |
| --- | --- |
| 400 | `BadRequest` |
| 401 | `Unauthorized` |
| 403 | `Forbidden` |
| 404 | `NotFound` |
| 409 | `Conflict` |
| 429 | `QuotaExceeded` |
| 503 | `Unavailable` |

`acquire()` and `session()` can also raise two exceptions of their own:
- `QueueTimeout` when the session is still queued after `queue_timeout`.
- `SessionFailed` when it ends FAILED or EXPIRED.

Both are `ShepherdError`s with `status=None`, and the session is already released when you see them. Network failures surface as the standard `urllib.error.URLError` or `OSError`. The API key never appears in exceptions or logs. The client logs to the `marathon_shepherd` logger.

## pytest

Installing the package registers a pytest plugin with a session-scoped `shepherd_session` fixture:

```python
def test_device_boots(shepherd_session):
    server = shepherd_session.adb_servers[0]
    ...
```

The fixture reads its configuration from the environment:

| Variable | Meaning |
| --- | --- |
| `MSH_URL` | Manager URL. Without it, tests that use the fixture are skipped. |
| `MSH_TOKEN` | API key. |
| `MSH_DEVICES` | How many devices to request (default 1). |
| `MSH_API` | API level selector, e.g. `>=34`. |
| `MSH_DEVICE_TYPE` | `physical` or `emulator`. |

The devices are acquired when a test first needs them and released when the run ends.

## API

The client has one method per manager operation. Sessions and devices come back as typed results, everything else as plain dicts:

- Probes, which need no key: `live()`, `ready()`, `health()`, `metrics()`, `openapi_document()`
- Sessions: `create_session()`, `get_session()`, `list_sessions(status, owner="me")`, `wait_for_session()`, `heartbeat()`, `extend_session()`, `release_session()`, plus the `acquire()` and `session()` helpers
- Devices: `list_devices(state, provider, device_type, api, labels, refresh)`, `get_device()`, `enter_maintenance()`, `leave_maintenance()`
- Events: `events(types, last_event_id)`
- Account and administration: `whoami()`, `audit()`, `list_providers()`, `register_provider()`, `deregister_provider()`, `get_config()`, `update_config()`, `reload_config()`, `list_clients()`, `create_client()`, `get_client()`, `update_client()`, `rotate_client_key()`, `revoke_client()`

## Development

```sh
python3 -m unittest discover -s clients/python/tests -t clients/python -v
./tests/run_tests.sh --only unit:python-client
```

The tests run against a stub manager on a loopback port and need nothing beyond Python.
