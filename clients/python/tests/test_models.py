"""Building typed results from the manager's JSON."""

from __future__ import annotations

import dataclasses
import pickle
import re
import unittest
from pathlib import Path

from marathon_shepherd import AdbServer, Device, DeviceList, Event, QueueTimeout, Session, __version__


class SessionTest(unittest.TestCase):
    DOCUMENT = {
        "id": "sess_1",
        "status": "READY",
        "requestedDevices": 2,
        "allocatedDevices": 1,
        "api": ">=34",
        "deviceType": "physical",
        "adbServers": [{"host": "10.0.0.5", "port": 5037}],
        "createdAt": "2026-09-12T10:00:00Z",
        "expiresAt": "2026-09-12T11:00:00Z",
        "owner": "ci",
        "metadata": {"job": "ui-tests"},
        "idleTimeoutSeconds": 120,
        "devices": [
            {"id": "rack-1:R58M123", "provider": "rack-1", "localId": "R58M123",
             "adbServer": {"host": "10.0.0.5", "port": 5037}, "apiLevel": "34"},
        ],
        "fieldFromANewerManager": {"nested": True},
    }

    def test_reads_fields_in_snake_case_and_keeps_the_raw_document(self) -> None:
        session = Session.from_json(self.DOCUMENT)

        self.assertTrue(session.is_ready)
        self.assertEqual((session.requested_devices, session.allocated_devices), (2, 1))
        self.assertEqual(session.adb_servers, (AdbServer("10.0.0.5", 5037),))
        self.assertEqual(session.devices[0].local_id, "R58M123")
        self.assertEqual(session.devices[0].adb_server, AdbServer("10.0.0.5", 5037))
        self.assertEqual(session.idle_timeout_seconds, 120)
        self.assertEqual(session.metadata, {"job": "ui-tests"})
        self.assertEqual(session.raw["fieldFromANewerManager"], {"nested": True})

    def test_missing_optional_fields_get_empty_defaults(self) -> None:
        session = Session.from_json({"id": "sess_2", "status": "PENDING"})

        self.assertTrue(session.is_pending)
        self.assertEqual((session.adb_servers, session.devices, session.metadata), ((), (), {}))
        self.assertIsNone(session.queue_position)
        self.assertIsNone(session.expires_at)

    def test_an_id_is_required(self) -> None:
        with self.assertRaises(ValueError):
            Session.from_json({"status": "READY"})

    def test_sessions_are_frozen_and_hashable(self) -> None:
        session = Session.from_json(self.DOCUMENT)

        with self.assertRaises(dataclasses.FrozenInstanceError):
            session.status = "RELEASED"  # type: ignore[misc]
        self.assertEqual(len({session, Session.from_json(dict(self.DOCUMENT))}), 1)


class DeviceTest(unittest.TestCase):
    def test_reads_a_device_and_its_maintenance(self) -> None:
        device = Device.from_json({
            "id": "rack-1:R58M123", "provider": "rack-1", "localId": "R58M123", "deviceType": "physical",
            "state": "maintenance", "labels": {"pool": "ci"},
            "maintenance": {"reason": "cracked screen", "by": "admin", "since": "2026-09-12T09:00:00Z"},
        })

        self.assertEqual((device.provider, device.local_id, device.state), ("rack-1", "R58M123", "maintenance"))
        self.assertEqual(device.labels, {"pool": "ci"})
        self.assertEqual(device.maintenance["reason"], "cracked screen")  # type: ignore[index]
        self.assertIsNone(device.api_level)

    def test_device_list_iterates_devices_and_keeps_pool_totals(self) -> None:
        devices = DeviceList.from_json({
            "providers": [{"name": "farm", "pool": {"available": 3, "busy": 1, "total": 4}}],
            "totalAvailable": 3,
            "totalBusy": 1,
            "devices": [{"id": "rack-1:A"}, {"id": "rack-1:B"}],
        })

        self.assertEqual([device.id for device in devices], ["rack-1:A", "rack-1:B"])
        self.assertEqual(len(devices), 2)
        self.assertEqual((devices.total_available, devices.providers[0]["name"]), (3, "farm"))


class AdbServerTest(unittest.TestCase):
    def test_adb_options_and_environment(self) -> None:
        server = AdbServer.from_json({"host": "10.0.0.5", "port": 5037})

        self.assertEqual(server.adb_args(), ["-H", "10.0.0.5", "-P", "5037"])
        self.assertEqual(server.env(), {"ANDROID_ADB_SERVER_ADDRESS": "10.0.0.5", "ANDROID_ADB_SERVER_PORT": "5037"})


class MiscTest(unittest.TestCase):
    def test_event_from_json(self) -> None:
        event = Event.from_json({"id": 7, "type": "session.ready", "at": "2026-09-12T10:00:00Z", "data": {"id": "s"}})
        self.assertEqual((event.id, event.type, event.data), (7, "session.ready", {"id": "s"}))

    def test_helper_exceptions_survive_pickling(self) -> None:
        error = pickle.loads(pickle.dumps(QueueTimeout("still queued", Session.from_json({"id": "s", "status": "PENDING"}))))
        self.assertEqual((error.message, error.session.id, error.status), ("still queued", "s", None))

    def test_version_matches_pyproject(self) -> None:
        pyproject = (Path(__file__).resolve().parents[1] / "pyproject.toml").read_text(encoding="utf-8")
        self.assertEqual(re.search(r'^version = "([^"]+)"', pyproject, re.MULTILINE).group(1), __version__)  # type: ignore[union-attr]
