"""Typed views of the manager's JSON documents.

``from_json`` accepts what the manager sends today and what a newer one may send:
unknown fields are ignored (they stay reachable through ``raw``, the full document)
and missing optional fields get empty defaults. Only identifying fields are required.
Timestamps are kept as the ISO 8601 strings the manager sends.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Iterator, List, Mapping, Optional, Tuple

PENDING = "PENDING"
READY = "READY"
FAILED = "FAILED"
RELEASED = "RELEASED"
EXPIRED = "EXPIRED"
#: Session statuses that never change again.
TERMINAL_STATUSES = frozenset({FAILED, RELEASED, EXPIRED})


def _required(data: Mapping[str, Any], key: str, kind: str) -> Any:
    value = data.get(key)
    if value is None:
        raise ValueError(f"{kind} JSON has no {key!r} field")
    return value


def _mapping(value: Any) -> Dict[str, Any]:
    return dict(value) if isinstance(value, Mapping) else {}


def _optional_int(value: Any) -> Optional[int]:
    try:
        return None if value is None else int(value)
    except (TypeError, ValueError):
        return None


@dataclass(frozen=True)
class AdbServer:
    """An adb server that reaches a session's devices.

    Point adb at it with ``adb -H host -P port ...`` (see :meth:`adb_args`), or export
    :meth:`env` so every adb call, including those tools make on their own, uses it.
    """

    host: str
    port: int
    raw: Dict[str, Any] = field(default_factory=dict, repr=False, compare=False)

    @classmethod
    def from_json(cls, data: Mapping[str, Any]) -> AdbServer:
        return cls(
            host=str(_required(data, "host", "AdbServer")),
            port=int(_required(data, "port", "AdbServer")),
            raw=dict(data),
        )

    def adb_args(self) -> List[str]:
        """The global adb options that select this server: ``["-H", host, "-P", port]``."""
        return ["-H", self.host, "-P", str(self.port)]

    def env(self) -> Dict[str, str]:
        """``ANDROID_ADB_SERVER_ADDRESS`` and ``ANDROID_ADB_SERVER_PORT`` for this server."""
        return {"ANDROID_ADB_SERVER_ADDRESS": self.host, "ANDROID_ADB_SERVER_PORT": str(self.port)}


@dataclass(frozen=True)
class SessionDevice:
    """A device a READY session holds; only providers that report devices list them."""

    id: str
    provider: str = ""
    local_id: str = ""
    adb_server: Optional[AdbServer] = None
    model: Optional[str] = None
    api_level: Optional[str] = None
    raw: Dict[str, Any] = field(default_factory=dict, repr=False, compare=False)

    @classmethod
    def from_json(cls, data: Mapping[str, Any]) -> SessionDevice:
        adb_server = data.get("adbServer")
        return cls(
            id=str(_required(data, "id", "SessionDevice")),
            provider=str(data.get("provider") or ""),
            local_id=str(data.get("localId") or ""),
            adb_server=AdbServer.from_json(adb_server) if isinstance(adb_server, Mapping) else None,
            model=data.get("model"),
            api_level=data.get("apiLevel"),
            raw=dict(data),
        )


@dataclass(frozen=True)
class Session:
    """A request for devices as the manager reports it.

    A PENDING session waits in the queue (``queue_position`` is 1-based); a READY one
    lists the adb servers to use in ``adb_servers`` and, for providers that report
    individual devices, the devices themselves in ``devices``.
    """

    id: str
    status: str
    requested_devices: int = 0
    allocated_devices: int = 0
    api: Optional[str] = None
    device_type: Optional[str] = None
    adb_servers: Tuple[AdbServer, ...] = ()
    queue_position: Optional[int] = None
    created_at: Optional[str] = None
    expires_at: Optional[str] = None
    owner: Optional[str] = None
    name: Optional[str] = None
    metadata: Dict[str, str] = field(default_factory=dict, hash=False)
    priority: int = 0
    idle_timeout_seconds: Optional[int] = None
    labels: Dict[str, str] = field(default_factory=dict, hash=False)
    device_ids: Tuple[str, ...] = ()
    devices: Tuple[SessionDevice, ...] = ()
    last_heartbeat_at: Optional[str] = None
    raw: Dict[str, Any] = field(default_factory=dict, repr=False, compare=False)

    @property
    def is_ready(self) -> bool:
        return self.status == READY

    @property
    def is_pending(self) -> bool:
        return self.status == PENDING

    @property
    def is_terminal(self) -> bool:
        """True for FAILED, RELEASED and EXPIRED: the session will never become READY."""
        return self.status in TERMINAL_STATUSES

    @classmethod
    def from_json(cls, data: Mapping[str, Any]) -> Session:
        return cls(
            id=str(_required(data, "id", "Session")),
            status=str(_required(data, "status", "Session")),
            requested_devices=_optional_int(data.get("requestedDevices")) or 0,
            allocated_devices=_optional_int(data.get("allocatedDevices")) or 0,
            api=data.get("api"),
            device_type=data.get("deviceType"),
            adb_servers=tuple(AdbServer.from_json(item) for item in data.get("adbServers") or ()),
            queue_position=_optional_int(data.get("queuePosition")),
            created_at=data.get("createdAt"),
            expires_at=data.get("expiresAt"),
            owner=data.get("owner"),
            name=data.get("name"),
            metadata=_mapping(data.get("metadata")),
            priority=_optional_int(data.get("priority")) or 0,
            idle_timeout_seconds=_optional_int(data.get("idleTimeoutSeconds")),
            labels=_mapping(data.get("labels")),
            device_ids=tuple(data.get("deviceIds") or ()),
            devices=tuple(SessionDevice.from_json(item) for item in data.get("devices") or ()),
            last_heartbeat_at=data.get("lastHeartbeatAt"),
            raw=dict(data),
        )


@dataclass(frozen=True)
class Device:
    """One device in the fleet, with its state and, while leased, the session holding it."""

    id: str
    provider: str = ""
    local_id: str = ""
    device_type: str = ""
    state: str = ""
    api_level: Optional[str] = None
    manufacturer: Optional[str] = None
    model: Optional[str] = None
    abi: Optional[str] = None
    labels: Dict[str, str] = field(default_factory=dict, hash=False)
    details: Dict[str, str] = field(default_factory=dict, hash=False)
    session_id: Optional[str] = None
    owner: Optional[str] = None
    #: ``{"reason", "by", "since"}`` while the device is in maintenance, else ``None``.
    maintenance: Optional[Dict[str, Any]] = field(default=None, hash=False)
    raw: Dict[str, Any] = field(default_factory=dict, repr=False, compare=False)

    @classmethod
    def from_json(cls, data: Mapping[str, Any]) -> Device:
        maintenance = data.get("maintenance")
        return cls(
            id=str(_required(data, "id", "Device")),
            provider=str(data.get("provider") or ""),
            local_id=str(data.get("localId") or ""),
            device_type=str(data.get("deviceType") or ""),
            state=str(data.get("state") or ""),
            api_level=data.get("apiLevel"),
            manufacturer=data.get("manufacturer"),
            model=data.get("model"),
            abi=data.get("abi"),
            labels=_mapping(data.get("labels")),
            details=_mapping(data.get("details")),
            session_id=data.get("sessionId"),
            owner=data.get("owner"),
            maintenance=dict(maintenance) if isinstance(maintenance, Mapping) else None,
            raw=dict(data),
        )


@dataclass(frozen=True)
class DeviceList:
    """The fleet inventory from ``GET /api/v1/devices``; iterating it yields the devices.

    ``devices`` only holds devices of providers that report them individually. Pool-only
    farms appear solely in ``providers`` (plain dicts with a ``pool`` summary), which is
    why the totals can exceed ``len(devices)``.
    """

    devices: Tuple[Device, ...] = ()
    providers: Tuple[Dict[str, Any], ...] = field(default=(), hash=False)
    total_available: int = 0
    total_busy: int = 0
    raw: Dict[str, Any] = field(default_factory=dict, repr=False, compare=False)

    def __iter__(self) -> Iterator[Device]:
        return iter(self.devices)

    def __len__(self) -> int:
        return len(self.devices)

    @classmethod
    def from_json(cls, data: Mapping[str, Any]) -> DeviceList:
        return cls(
            devices=tuple(Device.from_json(item) for item in data.get("devices") or ()),
            providers=tuple(_mapping(item) for item in data.get("providers") or ()),
            total_available=_optional_int(data.get("totalAvailable")) or 0,
            total_busy=_optional_int(data.get("totalBusy")) or 0,
            raw=dict(data),
        )


@dataclass(frozen=True)
class Event:
    """One event from the manager's live stream, e.g. ``session.ready`` or ``provider.down``.

    ``id`` is the stream position: pass it back as ``last_event_id`` to resume. Ids
    restart when the manager restarts.
    """

    id: Optional[int]
    type: str
    at: Optional[str] = None
    data: Dict[str, Any] = field(default_factory=dict, hash=False)
    raw: Dict[str, Any] = field(default_factory=dict, repr=False, compare=False)

    @classmethod
    def from_json(cls, data: Mapping[str, Any]) -> Event:
        return cls(
            id=_optional_int(data.get("id")),
            type=str(data.get("type") or ""),
            at=data.get("at"),
            data=_mapping(data.get("data")),
            raw=dict(data),
        )
