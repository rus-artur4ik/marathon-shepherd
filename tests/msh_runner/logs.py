"""Reading stage log files: progress markers, scenario tallies and failure excerpts.

Stages communicate with the runner only through what they write to their log, so
every marker format the runner understands is defined here.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from .settings import FAILURE_LOG_TAIL_LINES


# Matches one "completed task" line in any stage log.
# • ^> Task :   — Gradle task started/completed (build & unit stages)
# • \[OK\]      — Groovy unit test result
# • \[FAIL\]    — Groovy unit test failure
# • \bPASS\b    — e2e scenario passed  (standalone word, not "PASSED")
# • \bFAIL\b    — e2e scenario failed  (standalone word, not "FAILED")
_STAGE_TASK_RE = re.compile(r"^> Task :|\[OK\]|\[FAIL\]|\bPASS\b|\bFAIL\b")
_STAGE_PHASE_RE = re.compile(r"^MSH_PHASE:\s*(.+?)\s*$")
# Format: `MSH_TEST_PROGRESS: N/M [ optional-test-name ]`.  The name is free
# text after the count, typically the current test class/method; it's shown in
# the dashboard detail next to "testing [N/M]" so the user sees WHICH test is
# running, not just how many have started.
_STAGE_TEST_PROGRESS_RE = re.compile(
    r"^MSH_TEST_PROGRESS:\s*(\d+)\s*/\s*(\d+)(?:\s+(.+?))?\s*$"
)
_STAGE_FAILURE_CAUSE_RE = re.compile(r"^MSH_FAILURE_CAUSE:\s*(.+?)\s*$")
_STAGE_FAILURE_EXPECTED_RE = re.compile(r"^MSH_FAILURE_EXPECTED:\s*(.+?)\s*$")
_STAGE_FAILURE_ACTUAL_RE = re.compile(r"^MSH_FAILURE_ACTUAL:\s*(.+?)\s*$")
_STACKTRACE_LINE_RE = re.compile(
    r"^\s*(Traceback \(most recent call last\):|Caused by:|at .+\(.+\)|[\w.$]+(?:Exception|Error):)"
)


@dataclass
class ScenarioTally:
    passed: int = 0
    failed: int = 0
    skipped: int = 0

    def add(self, other: "ScenarioTally") -> None:
        self.passed += other.passed
        self.failed += other.failed
        self.skipped += other.skipped

    @property
    def total(self) -> int:
        return self.passed + self.failed + self.skipped


@dataclass
class FailureDetails:
    cause: str | None = None
    expected: str | None = None
    actual: str | None = None


def count_stage_scenarios(log_file: Path | None) -> ScenarioTally:
    """
    Count passed / failed / skipped scenarios from a stage log.

    Recognizes:
      - bash harnesses: bare `PASS` / `FAIL` / `SKIP` words on their own log line
      - groovy harness: `[OK]` / `[FAIL]` / `[SKIP]` markers
      - any line of the form `MSH_SCENARIO_RESULT: <PASS|FAIL|SKIP>`
    Falls back to zero counts on missing/empty logs.

    For Gradle stages whose scenario count lives in JUnit XMLs on disk,
    use `count_gradle_scenarios` instead — this function only reads the log.
    """
    if log_file is None or not log_file.is_file():
        return ScenarioTally()
    tally = ScenarioTally()
    # Fallback source of truth for bash harnesses that call stage_begin_test —
    # they emit MSH_TEST_PROGRESS: N/M on every scenario. The last N tells us
    # how many scenarios actually started; combined with SKIP markers and the
    # presence of a failure cause this gives accurate counts even when the
    # harness doesn't print explicit PASS/FAIL words.
    progress_max_started: int = 0
    progress_total: int = 0
    has_failure_cause: bool = False
    # Track explicit skip/fail markers separately so we can reconcile with
    # MSH_TEST_PROGRESS at the end.
    explicit_passed = 0
    explicit_failed = 0
    explicit_skipped = 0
    saw_any_explicit_marker = False
    try:
        with log_file.open("r", encoding="utf-8", errors="replace") as handle:
            for raw_line in handle:
                line = raw_line.strip()
                if not line:
                    continue
                # MSH_TEST_PROGRESS: N/M → monotonic counter of started scenarios.
                progress_match = _STAGE_TEST_PROGRESS_RE.match(line)
                if progress_match is not None:
                    progress_total = int(progress_match.group(2))
                    progress_max_started = max(progress_max_started, int(progress_match.group(1)))
                    continue
                if line.startswith("MSH_FAILURE_CAUSE:"):
                    has_failure_cause = True
                    continue
                # Structured marker wins if present.
                marker_match = _SCENARIO_MARKER_RE.match(line)
                if marker_match is not None:
                    verdict = marker_match.group(1).upper()
                    saw_any_explicit_marker = True
                    if verdict == "PASS":
                        explicit_passed += 1
                    elif verdict == "FAIL":
                        explicit_failed += 1
                    elif verdict == "SKIP":
                        explicit_skipped += 1
                    continue
                # Groovy-style markers.
                if "[OK]" in line:
                    explicit_passed += 1
                    saw_any_explicit_marker = True
                    continue
                if "[FAIL]" in line:
                    explicit_failed += 1
                    saw_any_explicit_marker = True
                    continue
                if "[SKIP]" in line:
                    explicit_skipped += 1
                    saw_any_explicit_marker = True
                    continue
                # Bash harnesses sometimes emit bare PASS / FAIL / SKIP on their
                # own line; also many scripts print `SKIP ...` via log_skip when
                # a scenario is intentionally skipped (e.g. cuttlefish without
                # a running orchestrator) without a preceding stage_begin_test.
                if _BARE_PASS_RE.match(line):
                    explicit_passed += 1
                    saw_any_explicit_marker = True
                    continue
                if _BARE_FAIL_RE.match(line):
                    explicit_failed += 1
                    saw_any_explicit_marker = True
                    continue
                if _BARE_SKIP_RE.match(line):
                    explicit_skipped += 1
                    saw_any_explicit_marker = True
                    continue
    except Exception:
        pass

    # Prefer MSH_TEST_PROGRESS when the harness emits it — it reflects the real
    # scenario count declared by `stage_runtime_init TOTAL_TEST_SCENARIOS`.
    # `stage_begin_test` implies a scenario started; unless it explicitly
    # emitted SKIP or FAILURE_CAUSE, scenarios that reach `stage_begin_test`
    # and continue past it are "passed". A stage that exited mid-way has
    # `has_failure_cause == True` and we attribute 1 to failed.
    if progress_max_started > 0:
        tally.skipped = explicit_skipped
        tally.failed = 1 if has_failure_cause else explicit_failed
        # Everything that started but wasn't explicitly skipped or failed is
        # counted as passed. Clamp to 0 on pathological inputs.
        tally.passed = max(0, progress_max_started - tally.skipped - tally.failed)
        return tally

    # No progress markers — fall back to whatever explicit markers we saw.
    tally.passed = explicit_passed
    tally.failed = explicit_failed
    tally.skipped = explicit_skipped
    return tally


_SCENARIO_MARKER_RE = re.compile(r"^MSH_SCENARIO_RESULT:\s*(PASS|FAIL|SKIP)\b", re.IGNORECASE)
# Bare-word matchers — single uppercase token on a line (allow trailing metadata).
_BARE_PASS_RE = re.compile(r"^PASS(?:\b|$)")
_BARE_FAIL_RE = re.compile(r"^FAIL(?:\b|$)")
_BARE_SKIP_RE = re.compile(r"^SKIP(?:\b|$)")


def count_gradle_scenarios(project_root: Path, module_paths: Sequence[str]) -> ScenarioTally:
    """
    Aggregate `tests=`, `failures=`, `errors=`, `skipped=` from JUnit XML reports
    for the given gradle modules. Used by unit stages where real scenario counts
    live on disk rather than in the stage log.
    """
    tally = ScenarioTally()
    for module_relative_path in module_paths:
        results_dir = project_root / module_relative_path / "build" / "test-results" / "test"
        if not results_dir.is_dir():
            continue
        for xml_path in results_dir.glob("TEST-*.xml"):
            try:
                header = xml_path.read_text(encoding="utf-8", errors="replace")[:2048]
            except Exception:
                continue
            tests = int((re.search(r'tests="(\d+)"', header) or [0, "0"])[1])
            failures = int((re.search(r'failures="(\d+)"', header) or [0, "0"])[1])
            errors = int((re.search(r'errors="(\d+)"', header) or [0, "0"])[1])
            skipped = int((re.search(r'skipped="(\d+)"', header) or [0, "0"])[1])
            tally.passed += max(0, tests - failures - errors - skipped)
            tally.failed += failures + errors
            tally.skipped += skipped
    return tally


def count_stage_progress(log_file: Path | None) -> int:
    """Count lines in *log_file* that match a known 'task completed' pattern."""
    if log_file is None or not log_file.is_file():
        return 0
    try:
        count = 0
        with log_file.open("r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if _STAGE_TASK_RE.search(line):
                    count += 1
        return count
    except OSError:
        return 0


def read_stage_runtime_markers(
    log_file: Path | None,
) -> tuple[str | None, tuple[int, int, str | None] | None]:
    """
    Scan the stage log for the most recent runtime markers.

    Returns `(phase, test_progress)` where **only one** of the two is ever
    non-None: whichever kind appeared later wins. This matters when a harness
    emits `MSH_PHASE: cleanup` (or any other phase) AFTER the last
    `MSH_TEST_PROGRESS: 14/14` — we want the dashboard to show "cleanup", not
    a stale "testing [14/14]".

    `test_progress` is a 3-tuple `(current, total, name)`. `name` is the
    optional trailing test name from `MSH_TEST_PROGRESS: 30/38 TasksTest.foo`
    — when present it's rendered next to the counter in the dashboard so the
    user sees WHICH test is running, not just how many have started.
    """
    if log_file is None or not log_file.is_file():
        return None, None
    lines = read_tail_lines(log_file, 200)

    def _progress_from(match: re.Match[str]) -> tuple[int, int, str | None] | None:
        current = int(match.group(1))
        total = int(match.group(2))
        name = match.group(3).strip() if match.group(3) else None
        if total <= 0:
            return None
        return current, total, name

    for line in reversed(lines):
        stripped = line.strip()
        match_phase = _STAGE_PHASE_RE.match(stripped)
        if match_phase:
            # "testing" phase lines are emitted alongside MSH_TEST_PROGRESS by
            # stage_begin_test — honor that pairing: read the progress from the
            # adjacent line instead of showing a bare "testing".
            phase_name = match_phase.group(1).strip()
            if phase_name.lower().startswith("testing"):
                idx = lines.index(line)
                for adjacent in lines[idx:idx + 4]:
                    match_progress = _STAGE_TEST_PROGRESS_RE.match(adjacent.strip())
                    if match_progress:
                        progress = _progress_from(match_progress)
                        if progress is not None:
                            return None, progress
                return phase_name, None
            return phase_name, None
        match_test = _STAGE_TEST_PROGRESS_RE.match(stripped)
        if match_test:
            progress = _progress_from(match_test)
            if progress is not None:
                return None, progress
    return None, None


def read_tail_lines(path: Path | None, line_count: int) -> list[str]:
    if path is None or not path.is_file() or path.stat().st_size == 0:
        return [""] * (line_count - 1) + ["(waiting for output)"]
    with path.open("rb") as file_handle:
        file_handle.seek(0, os.SEEK_END)
        file_size = file_handle.tell()
        block_size = min(file_size, 32_768)
        file_handle.seek(max(file_size - block_size, 0), os.SEEK_SET)
        content = file_handle.read().decode("utf-8", errors="replace")
    lines = content.splitlines()
    if not lines:
        return [""] * (line_count - 1) + ["(waiting for output)"]
    tail = lines[-line_count:]
    if len(tail) < line_count:
        tail = [""] * (line_count - len(tail)) + tail
    return tail


# Internal protocol markers the runner parses but the user doesn't need to see
# in the live Output tail or the failure report. They're kept in the log file
# itself — `--scan` still reads them from disk for timing analysis.
_INTERNAL_MARKER_RE = re.compile(
    r"^MSH_(?:PHASE|TEST_PROGRESS|SCENARIO_RESULT|FAILURE_(?:CAUSE|EXPECTED|ACTUAL)|PREBUILT_\w+):"
)


def read_display_tail_lines(path: Path | None, line_count: int) -> list[str]:
    """Same as `read_tail_lines` but strips internal `MSH_*` protocol markers.

    Those markers are meant for the runner's own parsers (phase/progress
    tracking, scenario counters, handoff coordination) — they carry no
    information for the human watching the dashboard and just add noise. This
    wrapper reads a larger block than requested so after filtering we still
    have `line_count` real log lines to show.
    """
    if path is None or not path.is_file() or path.stat().st_size == 0:
        return [""] * (line_count - 1) + ["(waiting for output)"]
    with path.open("rb") as file_handle:
        file_handle.seek(0, os.SEEK_END)
        file_size = file_handle.tell()
        # Read 2x the normal window so even marker-heavy logs (e.g. APK
        # instrumentation: 1 human line + 1 marker per test) leave us with
        # enough real content.
        block_size = min(file_size, 65_536)
        file_handle.seek(max(file_size - block_size, 0), os.SEEK_SET)
        content = file_handle.read().decode("utf-8", errors="replace")
    lines = [line for line in content.splitlines() if not _INTERNAL_MARKER_RE.match(line)]
    if not lines:
        return [""] * (line_count - 1) + ["(waiting for output)"]
    tail = lines[-line_count:]
    if len(tail) < line_count:
        tail = [""] * (line_count - len(tail)) + tail
    return tail


def read_failure_details(log_file: Path | None) -> FailureDetails | None:
    if log_file is None or not log_file.is_file():
        return None
    try:
        with log_file.open("r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError:
        return None

    details = FailureDetails()
    for raw_line in lines:
        line = raw_line.strip()
        match_cause = _STAGE_FAILURE_CAUSE_RE.match(line)
        if match_cause:
            details.cause = match_cause.group(1).strip()
            continue
        match_expected = _STAGE_FAILURE_EXPECTED_RE.match(line)
        if match_expected:
            details.expected = match_expected.group(1).strip()
            continue
        match_actual = _STAGE_FAILURE_ACTUAL_RE.match(line)
        if match_actual:
            details.actual = match_actual.group(1).strip()

    if details.cause or details.expected or details.actual:
        return details

    tail_lines = [line for line in read_display_tail_lines(log_file, FAILURE_LOG_TAIL_LINES) if line.strip()]
    if not tail_lines:
        return None

    last_error = next((line for line in reversed(tail_lines) if line.startswith("ERROR:")), None)
    if last_error is not None:
        return FailureDetails(cause=last_error.removeprefix("ERROR:").strip())

    stack_indexes = [index for index, line in enumerate(tail_lines) if _STACKTRACE_LINE_RE.match(line)]
    if stack_indexes:
        start = stack_indexes[-1]
        while start > 0 and _STACKTRACE_LINE_RE.match(tail_lines[start - 1]):
            start -= 1
        end = stack_indexes[-1]
        while end + 1 < len(tail_lines) and _STACKTRACE_LINE_RE.match(tail_lines[end + 1]):
            end += 1
        stacktrace = "\n".join(tail_lines[start : end + 1])
        return FailureDetails(cause=stacktrace)

    last_assertion = next(
        (
            line
            for line in reversed(tail_lines)
            if "Assertion failed:" in line or "Expected HTTP" in line or line.startswith("FAIL ")
        ),
        None,
    )
    if last_assertion is not None:
        return FailureDetails(cause=last_assertion.strip())

    return FailureDetails(cause=tail_lines[-1].strip())
