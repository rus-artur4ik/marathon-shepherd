"""The SSE parser and the reconnecting event stream."""

from __future__ import annotations

import itertools
import json
import unittest
from typing import Any, List

from marathon_shepherd import Event, Forbidden, ShepherdClient, Unauthorized
from marathon_shepherd.sse import SseMessage, SseParser

from .stub_manager import Reply, Stream, StubTestCase

EVENTS = "/api/v1/events"


def sse(event_id: int, event_type: str, **data: Any) -> str:
    """One event framed the way the manager writes it."""
    payload = {"id": event_id, "type": event_type, "at": "2026-09-12T10:00:00Z", "data": data}
    return f"id: {event_id}\nevent: {event_type}\ndata: {json.dumps(payload)}\n\n"


class SseParserTest(unittest.TestCase):
    @staticmethod
    def feed(parser: SseParser, text: str) -> List[SseMessage]:
        messages = (parser.feed(line) for line in text.splitlines(keepends=True))
        return [message for message in messages if message is not None]

    def test_parses_fields_joins_data_lines_and_skips_comments(self) -> None:
        parser = SseParser()

        messages = self.feed(parser, 'retry: 3000\n\n: keep-alive\n\nid: 7\nevent: session.ready\ndata: {"a":\ndata: 1}\n\n')

        self.assertEqual(messages, [SseMessage(id="7", event="session.ready", data='{"a":\n1}')])
        self.assertEqual((parser.retry_seconds, parser.last_event_id), (3.0, "7"))

    def test_accepts_crlf_and_fields_without_a_space(self) -> None:
        self.assertEqual(self.feed(SseParser(), "id:9\r\nevent:x\r\ndata:{}\r\n\r\n"), [SseMessage("9", "x", "{}")])

    def test_an_unfinished_message_is_dropped_without_moving_the_last_id(self) -> None:
        parser = SseParser("5")

        self.assertEqual(self.feed(parser, "id: 6\ndata: {}\n"), [])
        parser.reset()

        self.assertEqual(parser.last_event_id, "5")
        self.assertEqual(self.feed(parser, "data: {}\n\n"), [SseMessage("5", "message", "{}")])


class EventStreamTest(StubTestCase):
    def take(self, count: int, **options: Any) -> List[Event]:
        stream = self.client.events(**options)
        try:
            return list(itertools.islice(stream, count))
        finally:
            stream.close()

    def test_resumes_from_the_last_event_after_the_connection_drops(self) -> None:
        self.stub.route(
            "GET",
            EVENTS,
            Stream("retry: 10\n\n", ": keep-alive\n\n", sse(1, "session.created", id="sess_1"),
                   'id: 2\nevent: session.ready\ndata: {"id": 2', end="drop"),
            Stream(sse(2, "session.ready", id="sess_1"), ": keep-alive\n\n", sse(3, "provider.down", name="rack-1"),
                   end="hold"),
        )

        events = self.take(3, types=["session.", "provider."])

        self.assertEqual([(event.id, event.type) for event in events],
                         [(1, "session.created"), (2, "session.ready"), (3, "provider.down")])
        self.assertEqual(events[2].data, {"name": "rack-1"})
        first, second = self.stub.requests("GET", EVENTS)
        self.assertIsNone(first.header("Last-Event-ID"))
        self.assertEqual(second.header("Last-Event-ID"), "1")
        self.assertEqual(second.query, {"types": ["session.,provider."]})

    def test_reconnects_when_the_manager_ends_the_stream(self) -> None:
        self.stub.route(
            "GET",
            EVENTS,
            Stream("retry: 10\n\n", sse(4, "device.maintenance"), end="close"),
            Stream(sse(5, "device.available"), end="hold"),
        )

        events = self.take(2, last_event_id=3)

        self.assertEqual([event.id for event in events], [4, 5])
        self.assertEqual([request.header("Last-Event-ID") for request in self.stub.requests("GET", EVENTS)], ["3", "4"])

    def test_a_rejected_key_stops_the_stream(self) -> None:
        with self.assertRaises(Unauthorized):
            next(ShepherdClient(self.stub.url, token="msh_revoked").events())

        self.assertEqual(len(self.stub.requests("GET", EVENTS)), 1)

    def test_a_missing_role_stops_the_stream(self) -> None:
        self.stub.route("GET", EVENTS, Reply(403, {"error": "This operation needs the admin or user or viewer role"}))

        with self.assertRaises(Forbidden):
            next(self.client.events())

        self.assertEqual(len(self.stub.requests("GET", EVENTS)), 1)


if __name__ == "__main__":
    unittest.main()
