#!/usr/bin/env python3
import json
import os
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def env_int(name: str, default: int) -> int:
    raw_value = os.getenv(name)
    if raw_value is None or raw_value.strip() == "":
        return default
    try:
        return int(raw_value)
    except ValueError:
        raise SystemExit(f"{name} must be an integer")


HOST = os.getenv("FARM_SERVER_HOST", "0.0.0.0")
PORT = env_int("FARM_SERVER_PORT", 8080)
TOTAL = max(0, env_int("FARM_SERVER_TOTAL", 3))
INITIAL_AVAILABLE = max(0, env_int("FARM_SERVER_AVAILABLE", TOTAL))
if INITIAL_AVAILABLE > TOTAL:
    TOTAL = INITIAL_AVAILABLE


class FarmState:
    def __init__(self, total: int, available: int) -> None:
        self.total = total
        self.available = available
        self.busy = max(0, total - available)
        self.leases: dict[str, int] = {}
        self.lock = threading.Lock()

    def snapshot(self) -> dict[str, int]:
        return {
            "available": self.available,
            "busy": self.busy,
            "total": self.total,
        }

    def acquire(self, requested_count: int) -> dict[str, object]:
        with self.lock:
            acquired_count = min(max(requested_count, 0), self.available)
            if acquired_count <= 0:
                return {"leaseId": "", "acquiredCount": 0}
            lease_id = f"farm_{uuid.uuid4().hex[:8]}"
            self.leases[lease_id] = acquired_count
            self.available -= acquired_count
            self.busy += acquired_count
            return {"leaseId": lease_id, "acquiredCount": acquired_count}

    def release(self, lease_id: str) -> bool:
        with self.lock:
            acquired_count = self.leases.pop(lease_id, None)
            if acquired_count is None:
                return False
            self.available += acquired_count
            self.busy = max(0, self.busy - acquired_count)
            return True


STATE = FarmState(total=TOTAL, available=INITIAL_AVAILABLE)


class FarmHandler(BaseHTTPRequestHandler):
    server_version = "integration-farm-server/1.0"

    def _write_json(self, status_code: int, payload: dict[str, object]) -> None:
        encoded_payload = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded_payload)))
        self.end_headers()
        self.wfile.write(encoded_payload)

    def _read_json_body(self) -> dict[str, object]:
        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length <= 0:
            return {}
        body = self.rfile.read(content_length)
        if not body:
            return {}
        return json.loads(body.decode("utf-8"))

    def do_GET(self) -> None:
        if self.path == "/health":
            self._write_json(200, {"status": "healthy"})
            return
        if self.path == "/status":
            self._write_json(200, STATE.snapshot())
            return
        self._write_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path != "/acquire":
            self._write_json(404, {"error": "not found"})
            return
        try:
            payload = self._read_json_body()
        except json.JSONDecodeError:
            self._write_json(400, {"error": "invalid json"})
            return
        requested_count = payload.get("count", 0)
        try:
            requested_count = int(requested_count)
        except (TypeError, ValueError):
            self._write_json(400, {"error": "count must be an integer"})
            return
        result = STATE.acquire(requested_count)
        self._write_json(200, result)

    def do_DELETE(self) -> None:
        prefix = "/release/"
        if not self.path.startswith(prefix):
            self._write_json(404, {"error": "not found"})
            return
        lease_id = self.path[len(prefix):].strip()
        if lease_id == "":
            self._write_json(400, {"error": "lease id is required"})
            return
        released = STATE.release(lease_id)
        if released:
            self._write_json(200, {"status": "released"})
        else:
            self._write_json(404, {"error": "lease not found"})

    def log_message(self, fmt: str, *args: object) -> None:
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), FarmHandler)
    print(
        f"farm-server started on {HOST}:{PORT}; "
        f"available={STATE.available}, busy={STATE.busy}, total={STATE.total}",
        flush=True
    )
    server.serve_forever()
