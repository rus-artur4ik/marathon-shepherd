"""What the client sends, and how it reads answers and errors."""

from __future__ import annotations

import os
import unittest
from unittest import mock

from marathon_shepherd import (
    BadRequest,
    Conflict,
    Device,
    Forbidden,
    NotFound,
    QuotaExceeded,
    ShepherdClient,
    ShepherdError,
    Unauthorized,
    Unavailable,
)

from .stub_manager import TOKEN, Reply, StubTestCase, device_doc, session_doc


class AuthTest(StubTestCase):
    def test_sends_the_api_key_as_a_bearer_token(self) -> None:
        self.stub.route("GET", "/api/v1/me", Reply(200, {"id": "cli_1", "name": "ci", "role": "user"}))

        me = self.client.whoami()

        self.assertEqual(me["name"], "ci")
        request = self.stub.requests("GET", "/api/v1/me")[0]
        self.assertEqual(request.header("Authorization"), f"Bearer {TOKEN}")
        self.assertTrue(request.header("User-Agent").startswith("marathon-shepherd-python/"))  # type: ignore[union-attr]

    def test_reads_url_and_key_from_the_environment(self) -> None:
        self.stub.route("GET", "/api/v1/me", Reply(200, {"name": "ci"}))

        with mock.patch.dict(os.environ, {"MSH_URL": self.stub.url, "MSH_TOKEN": TOKEN}):
            client = ShepherdClient()

        self.assertEqual(client.whoami()["name"], "ci")

    def test_probes_need_no_key(self) -> None:
        self.stub.route("GET", "/live", Reply(200, {"status": "alive", "version": "1.0.0"}))

        self.assertEqual(ShepherdClient(self.stub.url, token="").live()["status"], "alive")
        self.assertIsNone(self.stub.requests("GET", "/live")[0].header("Authorization"))

    def test_a_rejected_key_raises_unauthorized_without_leaking_it(self) -> None:
        secret = "msh_wrong_secret_value"
        client = ShepherdClient(self.stub.url, token=secret)

        with self.assertLogs("marathon_shepherd", level="DEBUG") as logs:
            with self.assertRaises(Unauthorized) as caught:
                client.whoami()

        self.assertEqual(caught.exception.status, 401)
        self.assertIn("Missing or invalid API key", caught.exception.message)
        for text in (str(caught.exception), repr(caught.exception), repr(client), "\n".join(logs.output)):
            self.assertNotIn(secret, text)


class ErrorMappingTest(StubTestCase):
    def test_error_statuses_map_to_exception_classes(self) -> None:
        cases = {
            400: BadRequest,
            401: Unauthorized,
            403: Forbidden,
            404: NotFound,
            409: Conflict,
            429: QuotaExceeded,
            503: Unavailable,
            500: ShepherdError,
        }
        for status, expected in cases.items():
            with self.subTest(status=status):
                self.stub.route("GET", f"/api/v1/sessions/s{status}", Reply(status, {"error": f"reason {status}"}))

                with self.assertRaises(ShepherdError) as caught:
                    self.client.get_session(f"s{status}")

                self.assertIs(type(caught.exception), expected)
                self.assertEqual((caught.exception.status, caught.exception.message), (status, f"reason {status}"))

    def test_an_error_without_json_keeps_the_text(self) -> None:
        self.stub.route("GET", "/api/v1/me", Reply(502, text="upstream unavailable"))

        with self.assertRaises(ShepherdError) as caught:
            self.client.whoami()

        self.assertEqual((caught.exception.status, caught.exception.message), (502, "upstream unavailable"))

    def test_an_error_page_echoing_the_key_is_redacted(self) -> None:
        self.stub.route("GET", "/api/v1/me", Reply(502, text=f"proxy saw 'Authorization: Bearer {TOKEN}'"))

        with self.assertRaises(ShepherdError) as caught:
            self.client.whoami()

        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertIn("<redacted>", caught.exception.message)

    def test_health_returns_the_document_when_unhealthy(self) -> None:
        self.stub.route("GET", "/health", Reply(503, {"status": "unhealthy", "providersHealthy": 0}))

        self.assertEqual(self.client.health()["status"], "unhealthy")


class RequestBodyTest(StubTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.stub.route("POST", "/api/v1/sessions", Reply(201, session_doc()))

    def body_of(self, method: str, path: str) -> object:
        return self.stub.requests(method, path)[-1].body

    def test_create_session_omits_unset_fields(self) -> None:
        self.client.create_session(max_devices=2)

        self.assertEqual(self.body_of("POST", "/api/v1/sessions"), {"maxDevices": 2, "ttlSeconds": 3600, "priority": 0})

    def test_create_session_sends_given_fields_in_camel_case(self) -> None:
        self.client.create_session(
            max_devices=2, api=">=34", ttl_seconds=600, device_type="emulator", device_ids=["rack-1:A"],
            labels={"pool": "ci"}, name="build-42", metadata={"job": "ui"}, priority=5, idle_timeout_seconds=120,
        )

        self.assertEqual(self.body_of("POST", "/api/v1/sessions"), {
            "maxDevices": 2, "api": ">=34", "ttlSeconds": 600, "deviceType": "emulator", "deviceIds": ["rack-1:A"],
            "labels": {"pool": "ci"}, "name": "build-42", "metadata": {"job": "ui"}, "priority": 5,
            "idleTimeoutSeconds": 120,
        })

    def test_client_quota_is_nested_and_sent_only_when_set(self) -> None:
        self.stub.route("POST", "/api/v1/admin/clients", Reply(201, {"client": {"id": "cli_1"}, "apiKey": "msh_new"}))

        self.client.create_client("ci-bot")
        self.client.create_client("ci-bot", role="viewer", max_devices=4)

        bodies = [request.body for request in self.stub.requests("POST", "/api/v1/admin/clients")]
        self.assertEqual(bodies, [
            {"name": "ci-bot", "role": "user"},
            {"name": "ci-bot", "role": "viewer", "quota": {"maxDevices": 4}},
        ])

    def test_updating_a_client_sends_only_what_changes(self) -> None:
        self.stub.route("PATCH", "/api/v1/admin/clients/cli_1", Reply(200, {"id": "cli_1"}))

        self.client.update_client("cli_1", quota={"max_devices": 4})

        self.assertEqual(self.body_of("PATCH", "/api/v1/admin/clients/cli_1"), {"quota": {"maxDevices": 4}})

    def test_the_maintenance_reason_is_optional(self) -> None:
        path = "/api/v1/devices/rack-1:R58M123/maintenance"
        self.stub.route("PUT", path, Reply(200, device_doc(state="maintenance")))

        self.client.enter_maintenance("rack-1:R58M123")
        self.client.enter_maintenance("rack-1:R58M123", reason="cracked screen")

        self.assertEqual([request.body for request in self.stub.requests("PUT", path)], [{}, {"reason": "cracked screen"}])

    def test_a_long_poll_is_capped_at_thirty_seconds(self) -> None:
        self.stub.route("POST", "/api/v1/sessions/sess_1/wait", Reply(200, session_doc("PENDING")))

        self.client.wait_for_session("sess_1", timeout_seconds=90)

        self.assertEqual(self.body_of("POST", "/api/v1/sessions/sess_1/wait"), {"timeoutSeconds": 30})


class PathAndQueryTest(StubTestCase):
    def test_device_ids_are_percent_encoded_in_the_path(self) -> None:
        self.stub.route("GET", "/api/v1/devices/rack-1:R58M123", Reply(200, device_doc()))
        self.stub.route("DELETE", "/api/v1/devices/rack 1/R5/maintenance", Reply(200, {"status": "maintenance cleared"}))

        device = self.client.get_device("rack-1:R58M123")
        self.client.leave_maintenance("rack 1/R5")

        self.assertIsInstance(device, Device)
        self.assertEqual(device.local_id, "R58M123")
        self.assertEqual(self.stub.requests("GET")[0].raw_path, "/api/v1/devices/rack-1%3AR58M123")
        self.assertEqual(self.stub.requests("DELETE")[0].raw_path, "/api/v1/devices/rack%201%2FR5/maintenance")

    def test_device_filters_become_query_parameters(self) -> None:
        self.stub.route("GET", "/api/v1/devices", Reply(200, {
            "providers": [], "totalAvailable": 1, "totalBusy": 0, "devices": [device_doc()],
        }))

        devices = self.client.list_devices(
            state="available", device_type="physical", api=">=34", labels={"pool": "ci", "os": "14"}, refresh=True
        )

        self.assertEqual([device.id for device in devices], ["rack-1:R58M123"])
        self.assertEqual(self.stub.requests("GET", "/api/v1/devices")[0].query, {
            "state": ["available"], "deviceType": ["physical"], "api": [">=34"],
            "label": ["pool=ci", "os=14"], "refresh": ["true"],
        })

    def test_session_audit_and_admin_queries(self) -> None:
        self.stub.route("GET", "/api/v1/sessions", Reply(200, [session_doc(), session_doc("PENDING", "sess_2")]))
        self.stub.route("GET", "/api/v1/audit", Reply(200, {"entries": []}))
        self.stub.route("DELETE", "/api/v1/admin/clients/cli_1", Reply(200, {"id": "cli_1", "active": False}))

        sessions = self.client.list_sessions(owner="me")
        self.client.audit(action="session.", before=120, limit=50)
        self.client.revoke_client("cli_1", release_sessions=True)

        self.assertEqual([session.status for session in sessions], ["READY", "PENDING"])
        self.assertEqual(self.stub.requests("GET", "/api/v1/sessions")[0].query, {"owner": ["me"]})
        self.assertEqual(self.stub.requests("GET", "/api/v1/audit")[0].query,
                         {"action": ["session."], "before": ["120"], "limit": ["50"]})
        self.assertEqual(self.stub.requests("DELETE")[0].query, {"releaseSessions": ["true"]})


if __name__ == "__main__":
    unittest.main()
