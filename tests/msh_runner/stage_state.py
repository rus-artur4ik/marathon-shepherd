"""StageState behaviour of the TestRunner."""

from __future__ import annotations

import time

from .status import StatusEntry, format_elapsed


class StageStateMixin:
    """Status bookkeeping for prerequisite and stage rows: seeding, transitions and timing.

    State lives on TestRunner; this mixin only groups behaviour by concern.
    """

    def seed_prerequisite(self, label: str) -> None:
        self.prerequisites.append(StatusEntry(label=label, status="pending"))

    def seed_stage(self, label: str) -> None:
        self.stages.append(StatusEntry(label=label, status="pending"))

    # Terminal statuses trigger elapsed-time capture; "running" resets the clock.
    _TERMINAL_STATUSES = frozenset({"ok", "failed", "skip", "missing"})

    def _set_entry_status(
        self,
        entries: list[StatusEntry],
        label: str,
        status: str,
        detail: str = "",
    ) -> None:
        for entry in entries:
            if entry.label == label:
                entry.status = status
                entry.detail = detail
                self._update_entry_timing(entry, status)
                return
        new_entry = StatusEntry(label=label, status=status, detail=detail)
        self._update_entry_timing(new_entry, status)
        entries.append(new_entry)

    def _update_entry_timing(self, entry: StatusEntry, status: str) -> None:
        """Capture wall-clock per stage: mark start on running, record elapsed on terminal.

        On a successful terminal ("ok") we also swap the detail string with the
        formatted elapsed — the "OK" badge already signals success, so printing
        a success message next to it is redundant. For "failed"/"skip" we keep
        the caller-supplied detail because the reason (exit code / skip cause)
        is the only information that matters there.
        """
        if status == "running":
            # Only start the timer once — subsequent running updates are detail refreshes.
            if entry.start_time_monotonic is None:
                entry.start_time_monotonic = time.monotonic()
            return
        if status in self._TERMINAL_STATUSES and entry.start_time_monotonic is not None and entry.elapsed_seconds is None:
            entry.elapsed_seconds = time.monotonic() - entry.start_time_monotonic
            if status == "ok":
                entry.detail = format_elapsed(entry.elapsed_seconds)

    def set_prerequisite(self, label: str, status: str, detail: str = "") -> None:
        self._set_entry_status(self.prerequisites, label, status, detail)
        self.refresh()

    def start_stage(self, label: str, detail: str = "") -> None:
        self._set_entry_status(self.stages, label, "running", detail)
        self.current_stage_label = label
        self.refresh()

    def update_stage(self, status: str, detail: str = "") -> None:
        if self.current_stage_label is None:
            return
        self._set_entry_status(self.stages, self.current_stage_label, status, detail)
        self.current_stage_label = None
        self.refresh()

    def update_running_stage(self, detail: str) -> None:
        if self.current_stage_label is None:
            return
        self._set_entry_status(self.stages, self.current_stage_label, "running", detail)
        self.refresh()

    def refresh(self) -> None:
        if self.rich is not None:
            self.rich.refresh(self.config_lines, self.prerequisites, self.stages, self.current_log_file)
