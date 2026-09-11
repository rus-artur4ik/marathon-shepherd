"""The pytest plugin, exercised without pytest: a stand-in module replaces it."""

from __future__ import annotations

import importlib
import os
import subprocess
import sys
import types
import unittest
from pathlib import Path
from unittest import mock

from .stub_manager import TOKEN, Reply, StubTestCase, session_doc

PACKAGE_ROOT = Path(__file__).resolve().parents[1]


class Skipped(Exception):
    pass


class Failed(Exception):
    pass


def fake_pytest() -> types.ModuleType:
    """Just enough of pytest for the plugin: fixtures stay plain functions."""

    def skip(reason: str) -> None:
        raise Skipped(reason)

    def fail(reason: str, pytrace: bool = True) -> None:
        raise Failed(reason)

    module = types.ModuleType("pytest")
    module.fixture = lambda **options: (lambda function: function)  # type: ignore[attr-defined]
    module.skip = skip  # type: ignore[attr-defined]
    module.fail = fail  # type: ignore[attr-defined]
    return module


class PackageTest(unittest.TestCase):
    def test_the_package_imports_without_pytest(self) -> None:
        code = "import sys; sys.modules['pytest'] = None; import marathon_shepherd; print(marathon_shepherd.__version__)"
        result = subprocess.run([sys.executable, "-c", code], cwd=PACKAGE_ROOT, capture_output=True, text=True, timeout=60)

        self.assertEqual(result.returncode, 0, result.stderr)


class FixtureTest(StubTestCase):
    def load_plugin(self) -> types.ModuleType:
        modules = mock.patch.dict(sys.modules, {"pytest": fake_pytest()})
        modules.start()
        self.addCleanup(modules.stop)
        sys.modules.pop("marathon_shepherd.pytest_plugin", None)
        return importlib.import_module("marathon_shepherd.pytest_plugin")

    def test_leases_devices_described_by_the_environment_and_releases_them(self) -> None:
        self.stub.route("POST", "/api/v1/sessions", Reply(201, session_doc()))
        self.stub.route("DELETE", "/api/v1/sessions/sess_1", Reply(200, {"status": "released"}))
        plugin = self.load_plugin()
        environment = {"MSH_URL": self.stub.url, "MSH_TOKEN": TOKEN, "MSH_DEVICES": "2", "MSH_API": ">=34",
                       "MSH_DEVICE_TYPE": "emulator"}

        with mock.patch.dict(os.environ, environment):
            fixture = plugin.shepherd_session(plugin.shepherd_client())
            session = next(fixture)
            self.assertEqual(session.id, "sess_1")
            self.assertEqual(self.stub.requests("DELETE"), [])
            with self.assertRaises(StopIteration):
                next(fixture)  # what pytest does at teardown

        body = self.stub.requests("POST", "/api/v1/sessions")[0].body
        self.assertEqual((body["maxDevices"], body["api"], body["deviceType"]), (2, ">=34", "emulator"))
        self.assertEqual(len(self.stub.requests("DELETE", "/api/v1/sessions/sess_1")), 1)

    def test_skips_without_a_manager_url(self) -> None:
        plugin = self.load_plugin()

        with mock.patch.dict(os.environ, {"MSH_URL": ""}):
            with self.assertRaises(Skipped):
                plugin.shepherd_client()

    def test_rejects_a_device_count_that_is_not_a_number(self) -> None:
        plugin = self.load_plugin()

        with mock.patch.dict(os.environ, {"MSH_URL": self.stub.url, "MSH_DEVICES": "two"}):
            with self.assertRaises(Failed):
                next(plugin.shepherd_session(plugin.shepherd_client()))


if __name__ == "__main__":
    unittest.main()
