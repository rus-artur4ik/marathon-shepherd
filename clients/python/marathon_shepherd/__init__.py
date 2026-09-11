"""Python client for the Marathon Shepherd manager API.

Lease Android test devices from CI jobs, scripts and tests::

    import subprocess
    from marathon_shepherd import ShepherdClient

    client = ShepherdClient()  # $MSH_URL and $MSH_TOKEN
    with client.session(max_devices=2, api=">=34") as session:
        for server in session.adb_servers:
            subprocess.run(["adb", *server.adb_args(), "devices"], check=True)
"""

from __future__ import annotations

import logging

from ._version import __version__
from .client import ShepherdClient
from .errors import (
    BadRequest,
    Conflict,
    Forbidden,
    NotFound,
    QueueTimeout,
    QuotaExceeded,
    SessionFailed,
    ShepherdError,
    Unauthorized,
    Unavailable,
)
from .models import AdbServer, Device, DeviceList, Event, Session, SessionDevice

__all__ = [
    "AdbServer",
    "BadRequest",
    "Conflict",
    "Device",
    "DeviceList",
    "Event",
    "Forbidden",
    "NotFound",
    "QueueTimeout",
    "QuotaExceeded",
    "Session",
    "SessionDevice",
    "SessionFailed",
    "ShepherdClient",
    "ShepherdError",
    "Unauthorized",
    "Unavailable",
    "__version__",
]

# A library leaves output to the application: until logging is configured, stay quiet.
logging.getLogger("marathon_shepherd").addHandler(logging.NullHandler())
