"""acquire() and session(): queueing, cleanup on every way out, heartbeats."""

from __future__ import annotations

import time
import unittest
from typing import List

from marathon_shepherd import QueueTimeout, QuotaExceeded, Session, SessionFailed
from marathon_shepherd._heartbeat import interval_for_idle_timeout

from .stub_manager import Recorded, Reply, StubTestCase, session_doc, wait_until

CREATE = "/api/v1/sessions"
WAIT = "/api/v1/sessions/sess_1/wait"
HEARTBEAT = "/api/v1/sessions/sess_1/heartbeat"
RELEASE = "/api/v1/sessions/sess_1"


def slow_pending(request: Recorded) -> Reply:
    """A long poll that times out quickly, as a real one would after timeoutSeconds."""
    time.sleep(0.05)
    return Reply(200, session_doc("PENDING"))


class AcquireTest(StubTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.stub.route("DELETE", RELEASE, Reply(200, {"status": "released"}))

    def releases(self) -> List[Recorded]:
        return self.stub.requests("DELETE", RELEASE)

    def test_polls_a_pending_session_until_it_is_ready(self) -> None:
        self.stub.route("POST", CREATE, Reply(201, session_doc("PENDING", queuePosition=2)))
        self.stub.route("POST", WAIT, Reply(200, session_doc("PENDING", queuePosition=1)), Reply(200, session_doc("READY")))
        updates: List[Session] = []

        session = self.client.acquire(max_devices=1, api="34", on_update=updates.append)

        self.assertTrue(session.is_ready)
        self.assertEqual(session.adb_servers[0].port, 5037)
        self.assertEqual([(update.status, update.queue_position) for update in updates],
                         [("PENDING", 2), ("PENDING", 1), ("READY", None)])
        self.assertEqual([request.body for request in self.stub.requests("POST", WAIT)], [{"timeoutSeconds": 20}] * 2)
        self.assertEqual(self.releases(), [])

    def test_releases_the_session_when_the_queue_times_out(self) -> None:
        self.stub.route("POST", CREATE, Reply(201, session_doc("PENDING")))
        self.stub.route("POST", WAIT, slow_pending)

        with self.assertRaises(QueueTimeout) as caught:
            self.client.acquire(queue_timeout=0.3)

        self.assertEqual(caught.exception.session.id, "sess_1")  # type: ignore[union-attr]
        self.assertEqual(len(self.releases()), 1)
        polls = self.stub.requests("POST", WAIT)
        self.assertGreater(len(polls), 1)
        self.assertTrue(all(poll.body == {"timeoutSeconds": 1} for poll in polls), "a poll outlived the deadline")

    def test_releases_the_session_when_it_fails(self) -> None:
        self.stub.route("POST", CREATE, Reply(201, session_doc("PENDING")))
        self.stub.route("POST", WAIT, Reply(200, session_doc("FAILED")))

        with self.assertRaises(SessionFailed) as caught:
            self.client.acquire()

        self.assertEqual(caught.exception.session.status, "FAILED")  # type: ignore[union-attr]
        self.assertEqual(len(self.releases()), 1)

    def test_releases_the_session_when_the_caller_is_interrupted(self) -> None:
        self.stub.route("POST", CREATE, Reply(201, session_doc("PENDING")))

        def interrupt(session: Session) -> None:
            raise KeyboardInterrupt

        with self.assertRaises(KeyboardInterrupt):
            self.client.acquire(on_update=interrupt)

        self.assertEqual(len(self.releases()), 1)

    def test_nothing_is_released_when_creation_fails(self) -> None:
        self.stub.route("POST", CREATE, Reply(429, {"error": "Device quota exhausted"}))

        with self.assertRaises(QuotaExceeded):
            self.client.acquire()

        self.assertEqual(self.stub.requests("DELETE"), [])


class SessionContextTest(StubTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.stub.route("POST", CREATE, Reply(201, session_doc("READY")))
        self.stub.route("POST", HEARTBEAT, Reply(200, session_doc("READY")))
        self.stub.route("DELETE", RELEASE, Reply(200, {"status": "released"}))

    def heartbeats(self) -> List[Recorded]:
        return self.stub.requests("POST", HEARTBEAT)

    def test_releases_when_the_block_raises(self) -> None:
        with self.assertRaises(RuntimeError):
            with self.client.session(max_devices=2, api=">=34") as session:
                self.assertEqual(session.id, "sess_1")
                raise RuntimeError("a test step failed")

        self.assertEqual(len(self.stub.requests("DELETE", RELEASE)), 1)

    def test_heartbeats_while_the_block_runs_and_stops_after(self) -> None:
        with self.client.session(idle_timeout_seconds=30, heartbeat_interval=0.05):
            self.assertTrue(wait_until(lambda: len(self.heartbeats()) >= 2), "no heartbeats while the block ran")

        sent = len(self.heartbeats())
        time.sleep(0.2)
        self.assertEqual(len(self.heartbeats()), sent, "heartbeats continued after the block")
        self.assertEqual(len(self.stub.requests("DELETE", RELEASE)), 1)
        self.assertEqual(self.stub.requests("POST", CREATE)[0].body["idleTimeoutSeconds"], 30)

    def test_no_heartbeats_without_an_idle_timeout(self) -> None:
        with self.client.session():
            time.sleep(0.1)

        self.assertEqual(self.heartbeats(), [])
        self.assertEqual(len(self.stub.requests("DELETE", RELEASE)), 1)

    def test_a_failed_release_does_not_hide_the_blocks_error(self) -> None:
        self.stub.route("DELETE", RELEASE, Reply(500, {"error": "Internal server error"}))

        with self.assertLogs("marathon_shepherd", level="WARNING") as logs:
            with self.assertRaises(ValueError):
                with self.client.session():
                    raise ValueError("assertion in a test")

        self.assertIn("could not release session sess_1", "\n".join(logs.output))


class HeartbeatIntervalTest(unittest.TestCase):
    def test_a_third_of_the_idle_timeout_and_at_most_a_minute(self) -> None:
        self.assertEqual(interval_for_idle_timeout(30), 10)
        self.assertEqual(interval_for_idle_timeout(600), 60)


if __name__ == "__main__":
    unittest.main()
