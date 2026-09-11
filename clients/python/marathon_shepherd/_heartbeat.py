"""Background heartbeats that keep an idle-timed session alive while its owner works."""

from __future__ import annotations

import logging
import threading
from typing import Callable

from .errors import Forbidden, NotFound, Unauthorized

_log = logging.getLogger("marathon_shepherd")

#: However long the idle timeout, never go longer than this without a heartbeat.
MAX_HEARTBEAT_INTERVAL_SECONDS = 60.0


def interval_for_idle_timeout(idle_timeout_seconds: float) -> float:
    """A third of the idle timeout, so two lost heartbeats still leave time for a third,
    and at most a minute."""
    return min(idle_timeout_seconds / 3.0, MAX_HEARTBEAT_INTERVAL_SECONDS)


class Heartbeat:
    """Calls ``beat`` every ``interval`` seconds on a daemon thread until :meth:`stop`.

    A failed beat is logged and retried on the next tick, because one network blip
    should not cost the session. A beat the manager rejects for good (the session is
    gone, or the key may no longer hold it) ends the thread.
    """

    def __init__(self, beat: Callable[[], object], interval: float, label: str) -> None:
        self._beat = beat
        self._interval = interval
        self._label = label
        self._stopped = threading.Event()
        self._thread = threading.Thread(target=self._run, name=f"msh-heartbeat-{label}", daemon=True)

    def start(self) -> Heartbeat:
        self._thread.start()
        return self

    def stop(self, timeout: float = 5.0) -> None:
        """Stops the thread and waits for a beat in flight, so none is sent afterwards."""
        self._stopped.set()
        if self._thread.is_alive() and self._thread is not threading.current_thread():
            self._thread.join(timeout)

    def _run(self) -> None:
        while not self._stopped.wait(self._interval):
            try:
                self._beat()
            except (NotFound, Unauthorized, Forbidden) as error:
                _log.warning("stopping heartbeats for session %s: %s", self._label, error)
                return
            except Exception as error:  # keep beating through transient failures
                _log.warning("heartbeat for session %s failed, retrying in %.0fs: %s", self._label, self._interval, error)
