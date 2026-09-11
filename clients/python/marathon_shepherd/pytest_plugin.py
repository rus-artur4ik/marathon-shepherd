"""pytest plugin: a ``shepherd_session`` fixture that holds devices for the test run.

Installing the package registers it through the ``pytest11`` entry point. The
fixture is configured from the environment:

``MSH_URL``, ``MSH_TOKEN``
    The manager and the API key. Without ``MSH_URL``, tests using the fixture are
    skipped, so the suite still runs where there is no device farm.
``MSH_DEVICES``
    How many devices to ask for (default 1). An upper bound: READY means at least one.
``MSH_API``
    API level selector, e.g. ``34``, ``>=34`` or ``33..35``.
``MSH_DEVICE_TYPE``
    ``physical`` or ``emulator``.

Both fixtures are session-scoped: the devices are acquired the first time a test
asks for them and released when the run ends, however it ends.

This is the only module that imports pytest; the rest of the package works without it.
"""

from __future__ import annotations

import os
from typing import Any, Dict, Iterator, Mapping

import pytest

from .client import ShepherdClient
from .models import Session


def _session_options(environ: Mapping[str, str]) -> Dict[str, Any]:
    options: Dict[str, Any] = {"name": "pytest"}
    devices = environ.get("MSH_DEVICES", "").strip()
    if devices:
        if not devices.isdigit() or int(devices) < 1:
            pytest.fail(f"MSH_DEVICES must be a positive integer, got {devices!r}", pytrace=False)
        options["max_devices"] = int(devices)
    for option, variable in (("api", "MSH_API"), ("device_type", "MSH_DEVICE_TYPE")):
        value = environ.get(variable, "").strip()
        if value:
            options[option] = value
    return options


@pytest.fixture(scope="session")
def shepherd_client() -> ShepherdClient:
    """A client for ``$MSH_URL`` using ``$MSH_TOKEN``; skips the test when no URL is set."""
    if not os.environ.get("MSH_URL"):
        pytest.skip("MSH_URL is not set: no Marathon Shepherd manager to lease devices from")
    return ShepherdClient()


@pytest.fixture(scope="session")
def shepherd_session(shepherd_client: ShepherdClient) -> Iterator[Session]:
    """A READY session held for the whole run: point adb at ``session.adb_servers``."""
    with shepherd_client.session(**_session_options(os.environ)) as session:
        yield session
