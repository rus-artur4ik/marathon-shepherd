"""Server-sent events: an incremental parser and the reconnecting event reader.

The manager streams ``GET /api/v1/events`` as ``text/event-stream``: an ``id:``, an
``event:`` and one JSON ``data:`` line per event, a ``retry:`` hint when the stream
opens and a ``: keep-alive`` comment every 15 seconds.
"""

from __future__ import annotations

import http.client
import json
import logging
import time
from dataclasses import dataclass
from typing import Iterator, List, Optional

from ._transport import Transport
from .errors import ShepherdError
from .models import Event

_log = logging.getLogger("marathon_shepherd")

#: Reconnect delay until the server sends its own ``retry:`` hint.
DEFAULT_RETRY_SECONDS = 3.0
#: Upper bound for the growing delay between reconnects that receive nothing.
MAX_RETRY_SECONDS = 30.0
#: A stream silent this long is treated as dead: keep-alives arrive every 15 s.
MIN_READ_TIMEOUT_SECONDS = 45.0


@dataclass(frozen=True)
class SseMessage:
    """One complete SSE message, before its data is interpreted."""

    id: Optional[str]
    event: str
    data: str


class SseParser:
    """Turns ``text/event-stream`` lines into messages, following the SSE specification.

    Comment lines are skipped and ``retry:`` updates :attr:`retry_seconds`. The last
    event id only moves when a message completes, so a message cut off by a dropped
    connection is neither delivered nor skipped on reconnect: the server resends it.
    """

    def __init__(self, last_event_id: Optional[str] = None) -> None:
        self.last_event_id = last_event_id
        self.retry_seconds: Optional[float] = None
        self._id = last_event_id
        self._event = ""
        self._data: List[str] = []

    def feed(self, line: str) -> Optional[SseMessage]:
        """Consumes one line; returns a message when the line completes one."""
        line = line.rstrip("\n").rstrip("\r")
        if not line:
            return self._dispatch()
        if line.startswith(":"):
            return None
        name, _, value = line.partition(":")
        if value.startswith(" "):
            value = value[1:]
        if name == "data":
            self._data.append(value)
        elif name == "event":
            self._event = value
        elif name == "id" and "\0" not in value:
            self._id = value
        elif name == "retry" and value.isdigit():
            self.retry_seconds = int(value) / 1000.0
        return None

    def reset(self) -> None:
        """Forgets a half-received message, as the end of a stream requires."""
        self._id = self.last_event_id
        self._event = ""
        self._data = []

    def _dispatch(self) -> Optional[SseMessage]:
        self.last_event_id = self._id
        data, event = self._data, self._event
        self._data, self._event = [], ""
        if not data:
            return None
        return SseMessage(id=self.last_event_id, event=event or "message", data="\n".join(data))


def _to_event(message: SseMessage) -> Event:
    try:
        payload = json.loads(message.data)
    except ValueError:
        payload = None
    if not isinstance(payload, dict):
        # The manager always sends an object; keep anything else rather than drop it.
        payload = {"data": message.data}
    payload.setdefault("id", message.id)
    payload.setdefault("type", message.event)
    return Event.from_json(payload)


def read_events(transport: Transport, types: Optional[str], last_event_id: Optional[str]) -> Iterator[Event]:
    """Yields events until the generator is closed, reconnecting whenever the stream breaks.

    Each reconnect sends ``Last-Event-ID`` so the manager replays what was missed.
    Dropped connections, silent streams and 5xx answers are retried after the server's
    ``retry:`` delay, doubling (up to 30 s) while attempts receive nothing. 4xx answers
    are raised instead: retrying a rejected key, a missing role or a bad filter cannot help.
    """
    query = [("types", types)] if types else None
    parser = SseParser(last_event_id)
    attempt = 0
    while True:
        headers = {"Cache-Control": "no-cache"}
        if parser.last_event_id:
            headers["Last-Event-ID"] = parser.last_event_id
        received = False
        try:
            response = transport.open_stream(
                "/api/v1/events",
                query=query,
                headers=headers,
                timeout=max(transport.timeout, MIN_READ_TIMEOUT_SECONDS),
            )
            with response:
                for line in response:
                    received = True
                    message = parser.feed(line.decode("utf-8", errors="replace"))
                    if message is not None:
                        yield _to_event(message)
            reason = "the manager closed the stream"
        except ShepherdError as error:
            if error.status is None or error.status < 500:
                raise
            reason = str(error)
        except (OSError, http.client.HTTPException) as error:
            reason = f"{type(error).__name__}: {error}"
        finally:
            parser.reset()
        attempt = 1 if received else attempt + 1
        base = parser.retry_seconds if parser.retry_seconds is not None else DEFAULT_RETRY_SECONDS
        delay = min(base * 2 ** (attempt - 1), MAX_RETRY_SECONDS)
        _log.warning(
            "event stream interrupted (%s); reconnecting in %.1fs after event %s",
            reason,
            delay,
            parser.last_event_id or "none",
        )
        time.sleep(delay)
