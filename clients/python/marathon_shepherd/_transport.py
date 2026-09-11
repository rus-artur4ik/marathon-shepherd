"""HTTP plumbing: URLs, the API key, JSON bodies and error responses.

This is the only module that talks to ``urllib``. It logs one line per call (method,
path, status) and never headers or bodies: request bodies can carry adapter secrets
and responses can carry freshly issued API keys.
"""

from __future__ import annotations

import http.client
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Collection, Mapping, Optional, Sequence, Tuple

from ._version import __version__
from .errors import ShepherdError, error_for_status

_log = logging.getLogger("marathon_shepherd")

USER_AGENT = f"marathon-shepherd-python/{__version__}"
JSON = "application/json"

Query = Sequence[Tuple[str, str]]


def segment(value: str) -> str:
    """Percent-encodes one path segment: device ids such as ``rack-1:R58M123`` contain ':'."""
    text = str(value)
    if not text:
        raise ValueError("an id used in a URL path must not be empty")
    return urllib.parse.quote(text, safe="")


def _drain(error: urllib.error.HTTPError) -> bytes:
    """Reads an error response's body and closes it, so its connection is not leaked."""
    try:
        return error.read()
    except (OSError, http.client.HTTPException):
        return b""
    finally:
        error.close()


class Transport:
    """Sends requests to one manager, authenticated with one API key."""

    def __init__(self, base_url: str, token: Optional[str], timeout: float) -> None:
        if "://" not in base_url:
            raise ValueError(f"base_url needs a scheme, e.g. 'http://{base_url}'")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._token = token or None
        self._opener = urllib.request.build_opener()

    def request(
        self,
        method: str,
        path: str,
        *,
        query: Optional[Query] = None,
        body: Optional[Mapping[str, Any]] = None,
        accept: str = JSON,
        timeout: Optional[float] = None,
        allow_statuses: Collection[int] = (),
    ) -> Any:
        """Sends one request and returns the decoded body: parsed JSON, or text.

        Error statuses raise the matching :class:`ShepherdError`, except those in
        ``allow_statuses``: probes answer 503 with a regular document worth returning.
        """
        request = self._build(method, path, query, body, accept, None)
        started = time.monotonic()
        try:
            with self._opener.open(request, timeout=self._timeout(timeout)) as response:
                status, payload = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, payload = error.code, _drain(error)
            if status not in allow_statuses:
                self._log_call(method, path, status, started)
                raise self._error(status, payload, error.reason) from None
        self._log_call(method, path, status, started)
        return self._decode(payload, accept, f"{method} {path}", status)

    def open_stream(
        self,
        path: str,
        *,
        query: Optional[Query] = None,
        headers: Optional[Mapping[str, str]] = None,
        timeout: Optional[float] = None,
    ) -> http.client.HTTPResponse:
        """Opens a streaming GET and returns the live response, which the caller must close.

        ``timeout`` applies to every read, so a stream that goes silent raises
        ``socket.timeout`` instead of hanging forever.
        """
        request = self._build("GET", path, query, None, "text/event-stream", headers)
        started = time.monotonic()
        try:
            response = self._opener.open(request, timeout=self._timeout(timeout))
        except urllib.error.HTTPError as error:
            payload = _drain(error)
            self._log_call("GET", path, error.code, started)
            raise self._error(error.code, payload, error.reason) from None
        self._log_call("GET", path, response.status, started)
        return response

    def _build(
        self,
        method: str,
        path: str,
        query: Optional[Query],
        body: Optional[Mapping[str, Any]],
        accept: str,
        headers: Optional[Mapping[str, str]],
    ) -> urllib.request.Request:
        url = self.base_url + path
        if query:
            url += "?" + urllib.parse.urlencode(list(query))
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Accept", accept)
        request.add_header("User-Agent", USER_AGENT)
        if data is not None:
            request.add_header("Content-Type", JSON)
        for name, value in (headers or {}).items():
            request.add_header(name, value)
        if self._token:
            # Unredirected: should the manager URL redirect elsewhere, the key stays behind.
            request.add_unredirected_header("Authorization", f"Bearer {self._token}")
        return request

    def _timeout(self, timeout: Optional[float]) -> float:
        return self.timeout if timeout is None else timeout

    def _decode(self, payload: bytes, accept: str, call: str, status: int) -> Any:
        text = payload.decode("utf-8", errors="replace")
        if accept != JSON:
            return text
        if not text.strip():
            return None
        try:
            return json.loads(text)
        except ValueError:
            snippet = self._scrub(text[:200])
            raise ShepherdError(status, f"{call} answered with something other than JSON: {snippet!r}") from None

    def _error(self, status: int, payload: bytes, reason: Any) -> ShepherdError:
        """The exception for an error response; its message is the manager's ``error`` field."""
        text = payload.decode("utf-8", errors="replace").strip()
        message = text[:500] or str(reason or f"HTTP {status}")
        try:
            document = json.loads(text)
        except ValueError:
            document = None
        if isinstance(document, dict) and isinstance(document.get("error"), str):
            message = document["error"]
        return error_for_status(status, self._scrub(message))

    def _scrub(self, text: str) -> str:
        """Hides the API key, should a proxy echo the request back into an error page."""
        return text.replace(self._token, "<redacted>") if self._token else text

    @staticmethod
    def _log_call(method: str, path: str, status: int, started: float) -> None:
        _log.debug("%s %s -> %d (%.0f ms)", method, path, status, (time.monotonic() - started) * 1000)
