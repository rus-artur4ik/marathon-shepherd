"""The manager client: one method per API operation, plus acquire/session helpers."""

from __future__ import annotations

import contextlib
import logging
import math
import os
import time
from typing import Any, Callable, Dict, Iterable, Iterator, List, Mapping, Optional, Tuple, Union

from ._heartbeat import Heartbeat, interval_for_idle_timeout
from ._transport import Transport, segment
from .errors import NotFound, QueueTimeout, SessionFailed
from .models import Device, DeviceList, Event, Session
from .sse import read_events

_log = logging.getLogger("marathon_shepherd")

#: Where a manager listens unless configured otherwise.
DEFAULT_URL = "http://localhost:6037"
#: The manager holds a single long poll for at most this many seconds.
MAX_WAIT_SECONDS = 30
#: Seconds per long poll while :meth:`ShepherdClient.acquire` waits in the queue.
QUEUE_POLL_SECONDS = 20

UpdateCallback = Callable[[Session], Any]


def _compact(fields: Mapping[str, Any]) -> Dict[str, Any]:
    """Drops ``None`` values, so the manager applies its own defaults for them."""
    return {key: value for key, value in fields.items() if value is not None}


def _query(*pairs: Tuple[str, Any]) -> List[Tuple[str, str]]:
    """Query parameters for the pairs whose value is set; booleans become true/false."""
    query = []
    for name, value in pairs:
        if value is not None:
            query.append((name, ("true" if value else "false") if isinstance(value, bool) else str(value)))
    return query


def _quota(
    max_devices: Optional[int] = None,
    max_session_lifetime_seconds: Optional[int] = None,
    max_priority: Optional[int] = None,
) -> Dict[str, int]:
    return _compact(
        {
            "maxDevices": max_devices,
            "maxSessionLifetimeSeconds": max_session_lifetime_seconds,
            "maxPriority": max_priority,
        }
    )


def _strings(values: Union[str, Iterable[str]]) -> List[str]:
    return [values] if isinstance(values, str) else [str(value) for value in values]


class ShepherdClient:
    """Client for one Marathon Shepherd manager.

    ``base_url`` defaults to ``$MSH_URL``, then to ``http://localhost:6037``; ``token``,
    the API key, defaults to ``$MSH_TOKEN``. Without a key only the public probes work.
    ``timeout`` bounds each request in seconds; long polls and the event stream extend
    it as they need. The client keeps no connection state, so threads can share one.
    """

    def __init__(self, base_url: Optional[str] = None, token: Optional[str] = None, timeout: float = 30.0) -> None:
        if token is None:
            token = os.environ.get("MSH_TOKEN")
        self._transport = Transport(base_url or os.environ.get("MSH_URL") or DEFAULT_URL, token, timeout)

    @property
    def base_url(self) -> str:
        return self._transport.base_url

    @property
    def timeout(self) -> float:
        return self._transport.timeout

    def __repr__(self) -> str:
        return f"ShepherdClient(base_url={self.base_url!r})"  # never the key

    def _request(self, method: str, path: str, **options: Any) -> Any:
        return self._transport.request(method, path, **options)

    # ── Probes (public, no key needed) ───────────────────────────────────────

    def live(self) -> Dict[str, Any]:
        """``GET /live``: answers as long as the manager process runs."""
        return self._request("GET", "/live")

    def ready(self) -> Dict[str, Any]:
        """``GET /ready``: whether the manager can serve the API (its database is up).

        The 503 "not ready" answer is returned too, since it is the same document:
        check ``status`` (``ready`` or ``not-ready``).
        """
        return self._request("GET", "/ready", allow_statuses=(503,))

    def health(self) -> Dict[str, Any]:
        """``GET /health``: provider health, pool sizes and session counts.

        Returned for the 503 "no healthy provider" answer as well; check ``status``
        (``healthy``, ``degraded`` or ``unhealthy``).
        """
        return self._request("GET", "/health", allow_statuses=(503,))

    def metrics(self) -> str:
        """``GET /metrics``: Prometheus text exposition."""
        return self._request("GET", "/metrics", accept="text/plain")

    def openapi_document(self) -> str:
        """``GET /openapi.yaml``: the manager's OpenAPI 3.1 description, as YAML text."""
        return self._request("GET", "/openapi.yaml", accept="application/yaml")

    # ── Sessions ─────────────────────────────────────────────────────────────

    def create_session(
        self,
        max_devices: Optional[int] = None,
        api: Optional[str] = None,
        ttl_seconds: int = 3600,
        device_type: Optional[str] = None,
        device_ids: Optional[Iterable[str]] = None,
        labels: Optional[Mapping[str, str]] = None,
        name: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        priority: int = 0,
        idle_timeout_seconds: Optional[int] = None,
    ) -> Session:
        """Requests devices; returns a READY session, or a PENDING one waiting in the queue.

        ``max_devices`` is an upper bound, not a promise: READY means at least one
        device. ``api`` selects API levels (``34``, ``>=34``, ``33..35``...),
        ``device_type`` is ``physical`` or ``emulator``, ``device_ids`` restricts the
        session to those devices and ``labels`` requires them on every device. A PENDING
        session must be polled with :meth:`wait_for_session` or the manager drops it after
        90 s; :meth:`acquire` and :meth:`session` do that for you. Arguments left as
        ``None`` are not sent, so the manager's defaults apply. Raises
        :class:`QuotaExceeded` when the client's quota is used up and
        :class:`Unavailable` when no registered device could ever match.
        """
        body = _compact(
            {
                "maxDevices": max_devices,
                "api": None if api is None else str(api),
                "ttlSeconds": ttl_seconds,
                "deviceType": device_type,
                "deviceIds": None if device_ids is None else _strings(device_ids),
                "labels": None if labels is None else dict(labels),
                "name": name,
                "metadata": None if metadata is None else dict(metadata),
                "priority": priority,
                "idleTimeoutSeconds": idle_timeout_seconds,
            }
        )
        return Session.from_json(self._request("POST", "/api/v1/sessions", body=body))

    def get_session(self, session_id: str) -> Session:
        """One session, with its queue position while PENDING."""
        return Session.from_json(self._request("GET", f"/api/v1/sessions/{segment(session_id)}"))

    def list_sessions(self, status: Optional[str] = None, owner: Optional[str] = None) -> List[Session]:
        """Sessions visible to the caller; without ``status``, the PENDING, READY and FAILED ones.

        ``owner="me"`` keeps the caller's own sessions; any other value matches a client name.
        """
        documents = self._request("GET", "/api/v1/sessions", query=_query(("status", status), ("owner", owner)))
        return [Session.from_json(document) for document in documents or ()]

    def wait_for_session(self, session_id: str, timeout_seconds: float = 20) -> Session:
        """Long-polls a PENDING session: returns as soon as it leaves PENDING, or after
        ``timeout_seconds`` (1 to 30; larger values are capped) with it still PENDING.

        Every call also counts as a heartbeat. The manager drops a queued session that
        nobody waits on for 90 seconds, so keep calling until the session is READY.
        """
        seconds = max(1, min(MAX_WAIT_SECONDS, math.ceil(timeout_seconds)))
        document = self._request(
            "POST",
            f"/api/v1/sessions/{segment(session_id)}/wait",
            body={"timeoutSeconds": seconds},
            timeout=max(self.timeout, seconds + 10.0),
        )
        return Session.from_json(document)

    def heartbeat(self, session_id: str) -> Session:
        """Marks the session as in use, so a READY session with ``idle_timeout_seconds``
        is not released as idle; :meth:`session` sends these in the background."""
        return Session.from_json(self._request("POST", f"/api/v1/sessions/{segment(session_id)}/heartbeat"))

    def extend_session(self, session_id: str, ttl_seconds: int) -> Session:
        """Sets the session's remaining lifetime to ``ttl_seconds`` from now.

        The manager caps it at the client's ``maxSessionLifetimeSeconds`` and renews
        every lease of a READY session first; if a provider cannot renew, it raises
        :class:`Conflict` and nothing changes.
        """
        path = f"/api/v1/sessions/{segment(session_id)}/extend"
        return Session.from_json(self._request("POST", path, body={"ttlSeconds": ttl_seconds}))

    def release_session(self, session_id: str) -> Dict[str, Any]:
        """Gives every device back. Releasing twice succeeds; an unknown id raises :class:`NotFound`."""
        return self._request("DELETE", f"/api/v1/sessions/{segment(session_id)}")

    def acquire(
        self,
        max_devices: Optional[int] = None,
        api: Optional[str] = None,
        ttl_seconds: int = 3600,
        device_type: Optional[str] = None,
        device_ids: Optional[Iterable[str]] = None,
        labels: Optional[Mapping[str, str]] = None,
        name: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        priority: int = 0,
        idle_timeout_seconds: Optional[int] = None,
        *,
        queue_timeout: float = 900.0,
        on_update: Optional[UpdateCallback] = None,
    ) -> Session:
        """Creates a session with :meth:`create_session`'s arguments and waits until it is READY.

        ``on_update`` receives every state seen (the new session, each long poll, the
        READY one), e.g. to report the queue position. Whatever ends the wait early
        releases the session before the exception propagates: ``queue_timeout`` seconds
        passing (:class:`QueueTimeout`), the session ending FAILED or EXPIRED
        (:class:`SessionFailed`), an error, or the caller being interrupted,
        KeyboardInterrupt included. Nothing is left in the queue to take devices nobody
        will use. Releasing the READY session is then up to the caller; :meth:`session`
        does it for you.
        """
        deadline = time.monotonic() + queue_timeout
        session = self.create_session(
            max_devices, api, ttl_seconds, device_type, device_ids, labels, name, metadata, priority, idle_timeout_seconds
        )
        try:
            return self._wait_until_ready(session, deadline, queue_timeout, on_update)
        except BaseException:
            self._release_quietly(session.id)
            raise

    def _wait_until_ready(
        self, session: Session, deadline: float, queue_timeout: float, on_update: Optional[UpdateCallback]
    ) -> Session:
        position: Optional[int] = None
        while True:
            if on_update is not None:
                on_update(session)
            if session.is_ready:
                servers = ", ".join(f"{server.host}:{server.port}" for server in session.adb_servers) or "none"
                _log.info("session %s is ready: %d device(s) via %s", session.id, session.allocated_devices, servers)
                return session
            if not session.is_pending:
                raise SessionFailed(f"session {session.id} ended {session.status} before it was ready", session)
            if session.queue_position != position:
                position = session.queue_position
                _log.info("session %s is queued, position %s", session.id, position)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise QueueTimeout(
                    f"session {session.id} was still queued (position {position}) after {queue_timeout:g}s", session
                )
            session = self.wait_for_session(session.id, min(QUEUE_POLL_SECONDS, math.ceil(remaining)))

    @contextlib.contextmanager
    def session(
        self,
        max_devices: Optional[int] = None,
        api: Optional[str] = None,
        ttl_seconds: int = 3600,
        device_type: Optional[str] = None,
        device_ids: Optional[Iterable[str]] = None,
        labels: Optional[Mapping[str, str]] = None,
        name: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        priority: int = 0,
        idle_timeout_seconds: Optional[int] = None,
        *,
        queue_timeout: float = 900.0,
        on_update: Optional[UpdateCallback] = None,
        heartbeat_interval: Optional[float] = None,
    ) -> Iterator[Session]:
        """Holds devices for a ``with`` block: ``with client.session(max_devices=2) as s:``.

        Acquires like :meth:`acquire` on entry and always releases on exit, whether the
        block finishes, raises or is interrupted. When the session has an idle timeout,
        a daemon thread heartbeats every third of it (at least once a minute) while the
        block runs, so a long quiet test step is not mistaken for a crashed client;
        ``heartbeat_interval`` overrides that cadence. A failed release is logged, not
        raised, so it never hides the block's own exception; the manager reclaims the
        session when its TTL ends.
        """
        session = self.acquire(
            max_devices,
            api,
            ttl_seconds,
            device_type,
            device_ids,
            labels,
            name,
            metadata,
            priority,
            idle_timeout_seconds,
            queue_timeout=queue_timeout,
            on_update=on_update,
        )
        interval = heartbeat_interval
        idle_timeout = session.idle_timeout_seconds or idle_timeout_seconds
        if interval is None and idle_timeout:
            interval = interval_for_idle_timeout(idle_timeout)
        heartbeat: Optional[Heartbeat] = None
        try:
            if interval is not None:
                heartbeat = Heartbeat(lambda: self.heartbeat(session.id), interval, session.id).start()
            yield session
        finally:
            if heartbeat is not None:
                heartbeat.stop()
            self._release_quietly(session.id)

    def _release_quietly(self, session_id: str) -> None:
        """Releases on cleanup paths: logs instead of raising, so the original error wins."""
        try:
            self.release_session(session_id)
        except NotFound:
            _log.info("session %s was already gone", session_id)
        except Exception as error:
            _log.warning("could not release session %s: %s", session_id, error)
        else:
            _log.info("released session %s", session_id)

    # ── Devices ──────────────────────────────────────────────────────────────

    def list_devices(
        self,
        state: Optional[str] = None,
        provider: Optional[str] = None,
        device_type: Optional[str] = None,
        api: Optional[str] = None,
        labels: Optional[Mapping[str, str]] = None,
        refresh: bool = False,
    ) -> DeviceList:
        """The fleet: devices matching every given filter, plus each provider's pool summary.

        ``state`` is ``available``, ``busy``, ``offline`` or ``maintenance``; ``labels``
        requires each ``key=value`` pair. The manager answers from a background snapshot
        unless ``refresh=True``, which polls every adapter first and is slower.
        """
        query = _query(("state", state), ("provider", provider), ("deviceType", device_type), ("api", api))
        query += [("label", f"{key}={value}") for key, value in (labels or {}).items()]
        if refresh:
            query.append(("refresh", "true"))
        return DeviceList.from_json(self._request("GET", "/api/v1/devices", query=query))

    def get_device(self, device_id: str) -> Device:
        """One device by global id, ``<provider>:<device id>`` (e.g. ``rack-1:R58M123``)."""
        return Device.from_json(self._request("GET", f"/api/v1/devices/{segment(device_id)}"))

    def enter_maintenance(self, device_id: str, reason: Optional[str] = None) -> Device:
        """Keeps a device out of new sessions (admin only); a session holding it keeps it."""
        path = f"/api/v1/devices/{segment(device_id)}/maintenance"
        return Device.from_json(self._request("PUT", path, body=_compact({"reason": reason})))

    def leave_maintenance(self, device_id: str) -> Dict[str, Any]:
        """Makes a device allocatable again (admin only); succeeds if it was not in maintenance."""
        return self._request("DELETE", f"/api/v1/devices/{segment(device_id)}/maintenance")

    # ── Events ───────────────────────────────────────────────────────────────

    def events(
        self,
        types: Union[str, Iterable[str], None] = None,
        last_event_id: Union[int, str, None] = None,
    ) -> Iterator[Event]:
        """Streams manager events as they happen: a generator that runs until closed.

        ``types`` filters by type prefix, as ``"session.,provider."`` or a list of
        prefixes. A dropped connection is reopened with the id of the last event
        received, and the manager replays what was missed (from its buffer of the last
        1000 events; ids restart with the manager). To resume in another process, keep
        ``event.id`` and pass it as ``last_event_id``. Keep-alive comments are skipped.
        4xx answers, such as 401 or 403, raise instead of retrying.
        """
        type_filter = None if types is None else ",".join(_strings(types))
        resume_after = None if last_event_id is None else str(last_event_id)
        return read_events(self._transport, type_filter or None, resume_after)

    # ── Account and audit ────────────────────────────────────────────────────

    def whoami(self) -> Dict[str, Any]:
        """The calling key's client: name, role, quota in force and current usage."""
        return self._request("GET", "/api/v1/me")

    def audit(
        self,
        action: Optional[str] = None,
        actor: Optional[str] = None,
        target: Optional[str] = None,
        before: Optional[int] = None,
        limit: int = 100,
    ) -> Dict[str, Any]:
        """One page of the audit log, newest first: ``{"entries": [...], "nextBefore": id}``.

        ``action`` is a prefix such as ``session.``. Pass ``nextBefore`` back as
        ``before`` for the next, older page; it is absent on the last one. Only admins
        see other clients' entries and may filter by ``actor``.
        """
        query = _query(("action", action), ("actor", actor), ("target", target), ("before", before), ("limit", limit))
        return self._request("GET", "/api/v1/audit", query=query)

    # ── Providers ────────────────────────────────────────────────────────────

    def list_providers(self) -> List[Dict[str, Any]]:
        """Every known provider, from ``msh.yaml`` or self-registered, with health and liveness."""
        return self._request("GET", "/api/v1/providers")

    def register_provider(
        self,
        name: str,
        url: str,
        access_host: Optional[str] = None,
        secret: Optional[str] = None,
        adapter_type: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Registers an adapter or renews its registration (``provider`` role).

        Repeat it at least every ``heartbeatIntervalSeconds`` from the answer; a
        registration silent for ``ttlSeconds`` stops receiving sessions.
        """
        body = _compact({"name": name, "url": url, "accessHost": access_host, "secret": secret, "adapterType": adapter_type})
        return self._request("POST", "/api/v1/providers/register", body=body)

    def deregister_provider(self, name: str) -> Dict[str, Any]:
        """Removes a self-registered provider (admin only); refused while it holds leased devices."""
        return self._request("DELETE", f"/api/v1/providers/{segment(name)}")

    # ── Configuration ────────────────────────────────────────────────────────

    def get_config(self) -> Dict[str, Any]:
        """The active provider configuration (``msh.yaml``); adapter secrets read ``<redacted>``."""
        return self._request("GET", "/api/v1/config")

    def update_config(self, config: Mapping[str, Any]) -> Dict[str, Any]:
        """Replaces the configuration (admin only) and returns the one now in effect.

        A secret sent back as ``<redacted>`` keeps its stored value. Removing a provider
        that still holds leases raises :class:`Conflict`.
        """
        return self._request("PUT", "/api/v1/config", body=dict(config))

    def reload_config(self) -> Dict[str, Any]:
        """Makes the manager re-read ``msh.yaml`` from disk; returns the configuration in effect."""
        return self._request("POST", "/api/v1/config/reload")

    # ── API clients (admin) ──────────────────────────────────────────────────

    def list_clients(self, include_revoked: bool = False) -> List[Dict[str, Any]]:
        """API clients without their keys; revoked ones only with ``include_revoked``."""
        query = [("includeRevoked", "true")] if include_revoked else None
        return self._request("GET", "/api/v1/admin/clients", query=query)

    def create_client(
        self,
        name: str,
        role: str = "user",
        description: Optional[str] = None,
        max_devices: Optional[int] = None,
        max_session_lifetime_seconds: Optional[int] = None,
        max_priority: Optional[int] = None,
    ) -> Dict[str, Any]:
        """Creates an API client and returns ``{"client": {...}, "apiKey": "msh_..."}``.

        The key is shown exactly once: store it now. ``role`` is ``admin``, ``user``,
        ``viewer`` or ``provider``. Limits left as ``None`` fall back to the manager's
        default quota.
        """
        quota = _quota(max_devices, max_session_lifetime_seconds, max_priority)
        body = _compact({"name": name, "role": role, "description": description, "quota": quota or None})
        return self._request("POST", "/api/v1/admin/clients", body=body)

    def get_client(self, client_id: str) -> Dict[str, Any]:
        """One API client, with its explicit and effective quota."""
        return self._request("GET", f"/api/v1/admin/clients/{segment(client_id)}")

    def update_client(
        self,
        client_id: str,
        role: Optional[str] = None,
        description: Optional[str] = None,
        quota: Optional[Mapping[str, Optional[int]]] = None,
    ) -> Dict[str, Any]:
        """Changes a client's role, description or quota; ``None`` leaves a field as it is.

        ``quota`` replaces the whole quota and takes :meth:`create_client`'s limit names,
        e.g. ``{"max_devices": 4}``, which also lifts any lifetime or priority limit;
        ``{}`` lifts every limit.
        """
        body = _compact({"role": role, "description": description, "quota": None if quota is None else _quota(**quota)})
        return self._request("PATCH", f"/api/v1/admin/clients/{segment(client_id)}", body=body)

    def rotate_client_key(self, client_id: str) -> Dict[str, Any]:
        """Issues a new key; the old one stops working at once. The new key is shown exactly once."""
        return self._request("POST", f"/api/v1/admin/clients/{segment(client_id)}/rotate")

    def revoke_client(self, client_id: str, release_sessions: bool = False) -> Dict[str, Any]:
        """Revokes a client's key at once. Its sessions keep running, so revoking a CI key
        does not kill builds in flight, unless ``release_sessions`` is true."""
        query = [("releaseSessions", "true")] if release_sessions else None
        return self._request("DELETE", f"/api/v1/admin/clients/{segment(client_id)}", query=query)
