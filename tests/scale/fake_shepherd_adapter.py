#!/usr/bin/env python3
"""
Fake Shepherd adapter — implements the full AdapterContract over HTTP.

Used for scale and load testing without real devices. The manager sees this
as an ordinary adapter (adb/farm/cuttlefish), so allocation, queueing, failover,
and leasing paths are exercised end-to-end.

Configuration via environment variables:
  ADAPTER_PORT             — listen port (default 7037)
  ADAPTER_SECRET           — bearer token the manager must send (empty = no auth)
  ADAPTER_TYPE             — adapter type string reported in /health (default "fake")
  FAKE_DEVICE_COUNT        — pool size (default 100)
  FAKE_API_LEVELS          — comma-separated supported API levels (default "33,34,35")
  FAKE_DEVICE_TYPE         — "physical" or "emulator" (default "emulator")
  FAKE_LATENCY_MS          — fixed artificial latency per request (default 0)
  FAKE_ERROR_RATE          — probability of 500 per acquire, 0..1 (default 0)
  FAKE_ADVERTISED_ADB_HOST — host advertised in access descriptors (default "127.0.0.1")
  FAKE_ADVERTISED_ADB_PORT — base adb port; devices get port+i (default 5555)
  FAKE_SELECTIVE_API       — "true" (default) if the adapter supports selective api allocation
  FAKE_EPHEMERAL           — "true" if adapter should advertise ephemeral-vm feature

Endpoints:
  GET    /health
  GET    /status
  POST   /acquire
  DELETE /release/{leaseId}
"""
from __future__ import annotations

import json
import os
import random
import threading
import time
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def env_str(name: str, default: str) -> str:
    raw = os.getenv(name)
    return raw if raw is not None and raw.strip() != "" else default


def env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError:
        raise SystemExit(f"{name} must be an integer")


def env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw)
    except ValueError:
        raise SystemExit(f"{name} must be a number")


def env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


@dataclass
class FakeConfig:
    port: int = env_int("ADAPTER_PORT", 7037)
    secret: str = env_str("ADAPTER_SECRET", "")
    adapter_type: str = env_str("ADAPTER_TYPE", "fake")
    device_count: int = max(0, env_int("FAKE_DEVICE_COUNT", 100))
    api_levels: list[str] = field(
        default_factory=lambda: [level.strip() for level in env_str("FAKE_API_LEVELS", "33,34,35").split(",") if level.strip()]
    )
    device_type: str = env_str("FAKE_DEVICE_TYPE", "emulator")
    latency_ms: int = env_int("FAKE_LATENCY_MS", 0)
    error_rate: float = env_float("FAKE_ERROR_RATE", 0.0)
    advertised_host: str = env_str("FAKE_ADVERTISED_ADB_HOST", "127.0.0.1")
    advertised_port_base: int = env_int("FAKE_ADVERTISED_ADB_PORT", 5555)
    selective_api: bool = env_bool("FAKE_SELECTIVE_API", True)
    ephemeral: bool = env_bool("FAKE_EPHEMERAL", False)


@dataclass
class FakeDevice:
    serial: str
    api_level: str
    busy_lease: str | None = None


class FakeState:
    """Thread-safe state for the fake adapter."""

    def __init__(self, config: FakeConfig) -> None:
        self.config = config
        self.lock = threading.Lock()
        self.devices: list[FakeDevice] = []
        # Distribute devices across declared api levels round-robin.
        if config.api_levels:
            for index in range(config.device_count):
                api_level = config.api_levels[index % len(config.api_levels)]
                self.devices.append(FakeDevice(serial=f"fake-{config.adapter_type}-{index:04d}", api_level=api_level))
        self.leases: dict[str, list[str]] = {}  # leaseId -> list of serials

    def pool(self) -> dict[str, int]:
        total = len(self.devices)
        busy = sum(1 for device in self.devices if device.busy_lease is not None)
        return {"available": total - busy, "busy": busy, "total": total}

    def inventory(self) -> list[dict[str, object]]:
        # Group by api level.
        by_api: dict[str, int] = {}
        for device in self.devices:
            by_api[device.api_level] = by_api.get(device.api_level, 0) + 1
        profiles: list[dict[str, object]] = []
        for api_level, count in sorted(by_api.items()):
            profiles.append({
                "deviceType": self.config.device_type,
                "apiLevel": api_level,
                "count": count
            })
        return profiles

    def acquire(self, requested_count: int, api_level: str) -> dict[str, object]:
        with self.lock:
            free = [
                device for device in self.devices
                if device.busy_lease is None and (api_level == "any" or device.api_level == api_level)
            ]
            selected = free[:max(0, requested_count)]
            if not selected:
                return {"leaseId": None, "acquiredCount": 0, "serials": []}
            lease_id = f"fake_{uuid.uuid4().hex[:8]}"
            serials: list[str] = []
            for device in selected:
                device.busy_lease = lease_id
                serials.append(device.serial)
            self.leases[lease_id] = serials
            return {"leaseId": lease_id, "acquiredCount": len(serials), "serials": serials}

    def release(self, lease_id: str) -> bool:
        with self.lock:
            serials = self.leases.pop(lease_id, None)
            if serials is None:
                return False
            serial_set = set(serials)
            for device in self.devices:
                if device.serial in serial_set:
                    device.busy_lease = None
            return True


CONFIG = FakeConfig()
STATE = FakeState(CONFIG)


def _delay() -> None:
    if CONFIG.latency_ms > 0:
        time.sleep(CONFIG.latency_ms / 1000.0)


def _access(request_host: str) -> dict[str, object]:
    connection_id = f"{CONFIG.adapter_type}-primary-adb"
    return {
        "preferredConnectionId": connection_id,
        "connections": [{
            "id": connection_id,
            "protocol": "adb",
            "transport": "tcp",
            "host": CONFIG.advertised_host if CONFIG.advertised_host != "request" else request_host,
            "port": CONFIG.advertised_port_base,
            "exposure": "direct-tcp",
            "auth": {"type": "network", "metadata": {}},
            "metadata": {"scope": "fake", "managedBy": "adapter"}
        }],
        "metadata": {"adapterType": CONFIG.adapter_type}
    }


def _capabilities() -> dict[str, object]:
    features = ["inventory", "lease", "release"]
    if CONFIG.ephemeral:
        features.append("ephemeral-vm")
    return {
        "allocationModes": ["lease"],
        "supportedProtocols": ["adb"],
        "supportedExposureModes": ["direct-tcp"],
        "supportedDeviceTypes": [CONFIG.device_type],
        "supportedApiLevels": list(CONFIG.api_levels),
        "supportsSelectiveApiAllocation": CONFIG.selective_api,
        "supportsTestAccessAdb": True,
        "supportsTestAccessGrpc": False,
        "supportsTestAccessConsole": False,
        "features": features,
        "metadata": {"controlPlaneAuth": "bearer" if CONFIG.secret else "none"}
    }


class FakeHandler(BaseHTTPRequestHandler):
    server_version = "fake-shepherd-adapter/1.0"

    def _write_json(self, status_code: int, payload: dict[str, object] | list[object]) -> None:
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _read_json(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        body = self.rfile.read(length)
        return json.loads(body.decode("utf-8")) if body else {}

    def _authorized(self) -> bool:
        if not CONFIG.secret:
            return True
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return False
        return header[len("Bearer "):].strip() == CONFIG.secret

    def _request_host(self) -> str:
        host_header = self.headers.get("Host", "")
        return host_header.split(":")[0] if host_header else "127.0.0.1"

    def do_GET(self) -> None:
        _delay()
        if self.path == "/health":
            self._write_json(200, {"status": "healthy", "version": "0.1.0", "adapterType": CONFIG.adapter_type})
            return
        if self.path == "/status":
            if not self._authorized():
                self._write_json(401, {"error": "unauthorized"})
                return
            self._write_json(200, {
                "pool": STATE.pool(),
                "access": _access(self._request_host()),
                "inventory": STATE.inventory(),
                "capabilities": _capabilities(),
                "metadata": {}
            })
            return
        self._write_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        _delay()
        if self.path != "/acquire":
            self._write_json(404, {"error": "not found"})
            return
        if not self._authorized():
            self._write_json(401, {"error": "unauthorized"})
            return
        if CONFIG.error_rate > 0 and random.random() < CONFIG.error_rate:
            self._write_json(500, {"error": "simulated provider failure"})
            return
        try:
            payload = self._read_json()
        except json.JSONDecodeError:
            self._write_json(400, {"error": "invalid json"})
            return
        requested_count = int(payload.get("count", 0))
        api_level = str(payload.get("apiLevel", "any"))
        result = STATE.acquire(requested_count, api_level)
        if result["acquiredCount"] == 0:
            self._write_json(503, {"error": "No devices available"})
            return
        self._write_json(200, {
            "leaseId": result["leaseId"],
            "acquiredCount": result["acquiredCount"],
            "access": _access(self._request_host()),
            "inventory": STATE.inventory(),
            "capabilities": _capabilities(),
            "metadata": {"serials": ",".join(result["serials"])}
        })

    def do_DELETE(self) -> None:
        _delay()
        prefix = "/release/"
        if not self.path.startswith(prefix):
            self._write_json(404, {"error": "not found"})
            return
        if not self._authorized():
            self._write_json(401, {"error": "unauthorized"})
            return
        lease_id = self.path[len(prefix):].strip()
        if not lease_id:
            self._write_json(400, {"error": "lease id is required"})
            return
        if STATE.release(lease_id):
            self._write_json(200, {"status": "released"})
        else:
            self._write_json(500, {"error": f"Failed to release lease {lease_id}"})

    def log_message(self, fmt: str, *args: object) -> None:
        return


if __name__ == "__main__":
    random.seed(env_int("FAKE_SEED", 42))
    server = ThreadingHTTPServer(("0.0.0.0", CONFIG.port), FakeHandler)
    print(
        f"fake-shepherd-adapter type={CONFIG.adapter_type} port={CONFIG.port} "
        f"devices={CONFIG.device_count} apiLevels={','.join(CONFIG.api_levels)} "
        f"latency={CONFIG.latency_ms}ms errorRate={CONFIG.error_rate} "
        f"auth={'on' if CONFIG.secret else 'off'}",
        flush=True
    )
    server.serve_forever()
