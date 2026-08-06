"""Status rows shown on the dashboard, and how they are labelled and timed."""

from __future__ import annotations

import time
from dataclasses import dataclass


@dataclass
class StatusEntry:
    label: str
    status: str
    detail: str = ""
    # Wall-clock instrumentation. `start_time_monotonic` is set when the stage
    # enters the "running" state; `elapsed_seconds` is set once when it reaches
    # a terminal state (ok/failed/skip). `None` means the stage hasn't started
    # yet OR was skipped before it ever ran.
    start_time_monotonic: float | None = None
    elapsed_seconds: float | None = None


def format_elapsed(seconds: float | None) -> str:
    """Compact elapsed-time formatter used in dashboard + summary tables.

    Examples: 3.4s, 42.7s, 1m 23s, 12m 05s. Returns an empty string for None.
    """
    if seconds is None:
        return ""
    if seconds < 60:
        return f"{seconds:.1f}s"
    minutes = int(seconds // 60)
    rest = int(round(seconds - minutes * 60))
    if rest == 60:
        minutes += 1
        rest = 0
    return f"{minutes}m {rest:02d}s"


def current_timestamp() -> str:
    return time.strftime("%H:%M:%S")


def plain_status_label(status: str) -> str:
    mapping = {
        "pending": "[WAIT]",
        "ok": "[OK]",
        "running": "[RUN]",
        "skip": "[SKIP]",
        "missing": "[FAIL]",
        "failed": "[FAIL]",
    }
    return mapping.get(status, "[..]")
