"""A scriptable stand-in for the manager: a real HTTP server on a loopback port.

Tests route (method, path) pairs to replies. Like the manager, the stub rejects
``/api/v1`` requests that lack the right bearer key, and it records every request so
tests can assert on what the client actually sent.
"""

from __future__ import annotations

import json
import os
import threading
import time
import unittest
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, List, Optional, Tuple, Union
from urllib.parse import parse_qs, unquote, urlsplit

from marathon_shepherd import ShepherdClient

TOKEN = "msh_stub_secret_key"
UNAUTHORIZED = "Missing or invalid API key; send 'Authorization: Bearer <key>'"


def _keep_loopback_off_proxies() -> None:
    """A proxy configured on the host must not intercept requests meant for the stub."""
    for name in ("no_proxy", "NO_PROXY"):
        current = os.environ.get(name, "")
        if "127.0.0.1" not in current.split(","):
            os.environ[name] = ",".join(part for part in (current, "127.0.0.1", "localhost") if part)


_keep_loopback_off_proxies()


@dataclass
class Recorded:
    """One request as the stub received it."""

    method: str
    raw_path: str  # as sent, still percent-encoded
    path: str  # decoded, what routes match on
    query: Dict[str, List[str]]
    headers: Dict[str, str]  # lower-case names
    body: Any  # parsed JSON, or None without a body

    def header(self, name: str) -> Optional[str]:
        return self.headers.get(name.lower())


class Reply:
    """A complete response: a JSON document, or plain text."""

    def __init__(
        self,
        status: int = 200,
        document: Any = None,
        *,
        text: Optional[str] = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> None:
        self.status = status
        if text is not None:
            self.payload, self.content_type = text.encode(), "text/plain; charset=utf-8"
        else:
            self.payload = b"" if document is None else json.dumps(document).encode()
            self.content_type = "application/json"
        self.headers = headers or {}

    def write(self, handler: _Handler) -> None:
        handler.send_response(self.status)
        handler.send_header("Content-Type", self.content_type)
        handler.send_header("Content-Length", str(len(self.payload)))
        for name, value in self.headers.items():
            handler.send_header(name, value)
        handler.end_headers()
        handler.wfile.write(self.payload)


class Stream:
    """A chunked ``text/event-stream`` response, framed like the manager's.

    ``end`` says what follows the chunks: ``"close"`` ends the stream cleanly,
    ``"drop"`` cuts the connection mid-stream and ``"hold"`` keeps it open until the
    stub stops.
    """

    def __init__(self, *chunks: str, end: str = "close") -> None:
        self.chunks = chunks
        self.end = end

    def write(self, handler: _Handler) -> None:
        handler.send_response(200)
        handler.send_header("Content-Type", "text/event-stream")
        handler.send_header("Transfer-Encoding", "chunked")
        handler.end_headers()
        for chunk in self.chunks:
            data = chunk.encode()
            handler.wfile.write(b"%x\r\n%s\r\n" % (len(data), data))
        if self.end == "close":
            handler.wfile.write(b"0\r\n\r\n")
        elif self.end == "hold":
            handler.stub.stopped.wait(10)
        handler.close_connection = True


Answer = Union[Reply, Stream]
Route = Union[Answer, Callable[[Recorded], Answer]]


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def stub(self) -> StubManager:
        return self.server.stub  # type: ignore[attr-defined]

    def do_GET(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        raw_body = self.rfile.read(length) if length else b""
        url = urlsplit(self.path)
        request = Recorded(
            method=self.command,
            raw_path=url.path,
            path=unquote(url.path),
            query=parse_qs(url.query, keep_blank_values=True),
            headers={name.lower(): value for name, value in self.headers.items()},
            body=json.loads(raw_body) if raw_body else None,
        )
        self.stub.record(request)
        self.stub.answer(request).write(self)

    do_POST = do_PUT = do_PATCH = do_DELETE = do_GET

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 - the stdlib's signature
        pass  # keep the test output readable


class StubManager:
    """The stub server; start one per test and stop it in cleanup."""

    def __init__(self, token: str = TOKEN) -> None:
        self.token = token
        self.stopped = threading.Event()
        self._lock = threading.Lock()
        self._requests: List[Recorded] = []
        self._routes: Dict[Tuple[str, str], List[Route]] = {}
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self._server.stub = self  # type: ignore[attr-defined]
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self._thread.start()

    @property
    def url(self) -> str:
        host, port = self._server.server_address[:2]
        return f"http://{host}:{port}"

    def route(self, method: str, path: str, *answers: Route) -> None:
        """Answers ``method path`` with ``answers`` in turn; the last one repeats.

        An answer is a :class:`Reply`, a :class:`Stream`, or a function of the request.
        """
        with self._lock:
            self._routes[(method, path)] = list(answers)

    def requests(self, method: Optional[str] = None, path: Optional[str] = None) -> List[Recorded]:
        with self._lock:
            return [
                request
                for request in self._requests
                if (method is None or request.method == method) and (path is None or request.path == path)
            ]

    def record(self, request: Recorded) -> None:
        with self._lock:
            self._requests.append(request)

    def answer(self, request: Recorded) -> Answer:
        if request.path.startswith("/api/v1/") and request.header("Authorization") != f"Bearer {self.token}":
            return Reply(401, {"error": UNAUTHORIZED}, headers={"WWW-Authenticate": 'Bearer realm="marathon-shepherd"'})
        with self._lock:
            answers = self._routes.get((request.method, request.path))
            if not answers:
                return Reply(404, {"error": f"the stub has no route for {request.method} {request.path}"})
            answer = answers.pop(0) if len(answers) > 1 else answers[0]
        return answer(request) if callable(answer) else answer

    def stop(self) -> None:
        self.stopped.set()
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(5)


class StubTestCase(unittest.TestCase):
    """A fresh stub per test, and a client holding the stub's key."""

    def setUp(self) -> None:
        self.stub = StubManager()
        self.addCleanup(self.stub.stop)
        self.client = ShepherdClient(self.stub.url, token=TOKEN, timeout=5.0)


def session_doc(status: str = "READY", session_id: str = "sess_1", **fields: Any) -> Dict[str, Any]:
    """A Session document shaped like the manager's; READY sessions hold one device."""
    ready = status == "READY"
    document: Dict[str, Any] = {
        "id": session_id,
        "status": status,
        "requestedDevices": 1,
        "allocatedDevices": 1 if ready else 0,
        "adbServers": [{"host": "10.0.0.5", "port": 5037}] if ready else [],
        "createdAt": "2026-09-12T10:00:00Z",
        "expiresAt": "2026-09-12T11:00:00Z",
    }
    if status == "PENDING":
        document["queuePosition"] = 1
    document.update(fields)
    return document


def device_doc(device_id: str = "rack-1:R58M123", **fields: Any) -> Dict[str, Any]:
    """A Device document shaped like the manager's."""
    provider, _, local_id = device_id.partition(":")
    document: Dict[str, Any] = {
        "id": device_id,
        "provider": provider,
        "localId": local_id,
        "deviceType": "physical",
        "state": "available",
        "apiLevel": "34",
    }
    document.update(fields)
    return document


def wait_until(condition: Callable[[], bool], timeout: float = 3.0) -> bool:
    """Polls ``condition`` until it holds or ``timeout`` passes; returns its last value."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(0.01)
    return condition()
