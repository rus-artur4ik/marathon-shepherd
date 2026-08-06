"""Reporting behaviour of the TestRunner."""

from __future__ import annotations

import re
from pathlib import Path

from .logs import ScenarioTally, count_gradle_scenarios, count_stage_scenarios, read_display_tail_lines, read_failure_details
from .settings import FAILURE_LOG_TAIL_LINES, REPO_ROOT
from .status import StatusEntry, format_elapsed


class ReportingMixin:
    """End-of-run reporting: configuration echo, failure excerpts, scenario totals and --scan.

    State lives on TestRunner; this mixin only groups behaviour by concern.
    """

    def print_plain_configuration(self) -> None:
        self.plain.print_step("Runner configuration")
        for line in self.config_lines:
            self.plain.print_info(line)

    def print_plain_prerequisites(self) -> None:
        self.plain.print_step("Checking prerequisites")
        self.plain.print_status_entries(self.prerequisites)

    def print_failure_excerpt(self, stage_label: str, log_file: Path) -> None:
        width = 72
        bar = "━" * width
        thin = "─" * width
        failure_details = read_failure_details(log_file)
        print(f"\n{bar}")
        print(f"  FAILED: {stage_label}")
        print(thin)
        if failure_details is not None and any(
            value for value in (failure_details.cause, failure_details.expected, failure_details.actual)
        ):
            print("  Cause:")
            if failure_details.cause:
                for line in failure_details.cause.splitlines():
                    print(f"    {line}")
            if failure_details.expected:
                print(f"    expected: {failure_details.expected}")
            if failure_details.actual:
                print(f"    actual:   {failure_details.actual}")
            print(thin)
        if not log_file.is_file() or log_file.stat().st_size == 0:
            print("  (no output captured)")
        else:
            # `read_display_tail_lines` already drops MSH_* protocol markers
            # — no need to filter FAILURE_* separately below.
            lines = read_display_tail_lines(log_file, FAILURE_LOG_TAIL_LINES)
            while lines and not lines[0].strip():
                lines = lines[1:]
            for line in lines:
                print(f"  {line}")
        print(f"{bar}\n")

    def print_failure_report(self) -> None:
        """Print a consolidated failure report for all failed stages to stdout."""
        if not self.failed_stage_logs:
            return
        width = 72
        double = "═" * width
        count = len(self.failed_stage_logs)
        print(f"\n{double}")
        print(f"  FAILURE REPORT  ({count} stage{'s' if count != 1 else ''} failed)")
        print(double)
        for failed_label, log_file in self.failed_stage_logs:
            self.print_failure_excerpt(failed_label, log_file)
        print(f"Full logs: {self.log_root}")

    def _aggregate_scenario_totals(self) -> tuple[ScenarioTally, list[tuple[str, ScenarioTally]]]:
        """
        Walk each stage log and return (grand_total, per_stage_list).

        For gradle unit stages the JUnit XML reports are authoritative; for
        everything else we scan the stage log for PASS/FAIL/SKIP markers.
        Skipped stages contribute zeros.
        """
        grand = ScenarioTally()
        per_stage: list[tuple[str, ScenarioTally]] = []
        for entry in self.stages:
            if entry.label == "Runner configuration" or entry.label == "Checking prerequisites" or entry.label == "Final summary":
                continue
            if entry.status == "skip":
                continue
            log_slug = "".join(c.lower() if c.isalnum() else "-" for c in entry.label).strip("-")
            log_file = self.log_root / f"{log_slug}.log"
            if entry.label == "[unit] kotlin":
                tally = count_gradle_scenarios(REPO_ROOT, [
                    "manager/service",
                    "adapter/shepherd-adb",
                    "adapter/shepherd-cuttlefish",
                ])
            elif entry.label == "[build] all":
                # installDist emits no test scenarios.
                tally = ScenarioTally()
            elif entry.label == "[build] docker images":
                tally = ScenarioTally()
            else:
                tally = count_stage_scenarios(log_file if log_file.exists() else None)
            per_stage.append((entry.label, tally))
            grand.add(tally)
        return grand, per_stage

    def _print_scenario_summary(
        self,
        totals: ScenarioTally,
        per_stage: list[tuple[str, ScenarioTally]],
        stage_counts: dict[str, int],
    ) -> None:
        header = (
            f"Stages: {stage_counts['ok']} passed, "
            f"{stage_counts['failed']} failed, "
            f"{stage_counts['skipped']} skipped"
        )
        scenarios_line = (
            f"Scenarios: {totals.passed} passed, "
            f"{totals.failed} failed, "
            f"{totals.skipped} skipped "
            f"({totals.total} total)"
        )
        # Default summary is intentionally terse — users who want per-stage
        # timings and scenario breakdowns use `--scan` (see _print_scan_report).
        width = 72
        bar = "━" * width
        thin = "─" * width
        if self.use_plain_logs:
            print(f"\n{bar}")
            print("  TEST SCENARIO TOTALS")
            print(thin)
            print(f"  {header}")
            print(f"  {scenarios_line}")
            print(f"{bar}")
        else:
            print(f"\n{header}")
            print(scenarios_line)

    # ── --scan: detailed timing report ───────────────────────────────────────

    _LOG_TIMESTAMP_RE = re.compile(r"^\[(\d{2}):(\d{2}):(\d{2})\]\s*(.+?)\s*$")

    @staticmethod
    def _parse_stage_timeline(log_file: Path) -> list[tuple[float, str]]:
        """Extract [HH:MM:SS] events from a stage log → list of (seconds_from_start, message).

        Only counts events that carry a log_step-style message (lines that start
        with a timestamp prefix). The first event is anchored at t=0; every
        subsequent event carries its delta from the first timestamp.
        """
        events: list[tuple[float, str]] = []
        first_ts: int | None = None
        if not log_file.is_file():
            return events
        try:
            with log_file.open("r", encoding="utf-8", errors="replace") as handle:
                for line in handle:
                    match = ReportingMixin._LOG_TIMESTAMP_RE.match(line)
                    if not match:
                        continue
                    hh, mm, ss, message = match.groups()
                    ts = int(hh) * 3600 + int(mm) * 60 + int(ss)
                    if first_ts is None:
                        first_ts = ts
                    delta = ts - first_ts
                    if delta < 0:  # clock rolled over midnight
                        delta += 24 * 3600
                    events.append((float(delta), message))
        except Exception:
            return events
        return events

    def _print_scan_report(self, total_elapsed: float) -> None:
        """Print a detailed per-stage timing breakdown (triggered by --scan).

        Sections:
          1. Headline: total wall time, sum of stages, parallel savings
          2. Stages ranked by wall-clock time (bottleneck identification)
          3. Per-stage event timeline (from [HH:MM:SS] log lines)
        """
        width = 78
        bar = "═" * width
        thin = "─" * width

        test_stages = [
            entry for entry in self.stages
            if entry.label not in {"Runner configuration", "Checking prerequisites", "Final summary"}
            and entry.status != "skip"
            and entry.elapsed_seconds is not None
        ]
        sum_of_stages = sum(entry.elapsed_seconds or 0 for entry in test_stages)
        parallel_savings = max(0.0, sum_of_stages - total_elapsed)

        print(f"\n{bar}")
        print("  TIMING SCAN — where does the time go?")
        print(bar)
        print(f"  Total wall time         {format_elapsed(total_elapsed):>10s}")
        print(f"  Sum of per-stage times  {format_elapsed(sum_of_stages):>10s}"
              + (f"  (→ {format_elapsed(parallel_savings)} saved via parallelism)"
                 if parallel_savings > 0.5 else ""))
        print()

        # ── Ranking: slowest stages first ────────────────────────────────────
        ranked = sorted(test_stages, key=lambda e: e.elapsed_seconds or 0, reverse=True)
        if ranked:
            print("  Stages by wall-clock time (slowest first):")
            print(thin)
            max_elapsed = max(e.elapsed_seconds or 0 for e in ranked) or 1.0
            for entry in ranked:
                status_glyph = {"ok": "✓", "failed": "✗"}.get(entry.status, "·")
                pct = int((entry.elapsed_seconds or 0) / total_elapsed * 100) if total_elapsed > 0 else 0
                bar_len = int((entry.elapsed_seconds or 0) / max_elapsed * 24)
                meter = "█" * bar_len + "·" * (24 - bar_len)
                print(
                    f"   {status_glyph} {entry.label:<40s}  "
                    f"{format_elapsed(entry.elapsed_seconds):>8s}  "
                    f"{pct:>3d}%  {meter}"
                )
            print()

        # ── Per-stage event timelines ────────────────────────────────────────
        # Only shell-based harnesses (component:cli, component:docker,
        # integration, e2e, scale) emit `[HH:MM:SS]` prefixed log lines —
        # Gradle/Groovy stages write raw tool output, so they won't show up
        # here. That's intentional: their timings are in the ranking above.
        stages_with_timelines: list[tuple[StatusEntry, list[tuple[float, str]]]] = []
        for entry in ranked:
            log_slug = "".join(c.lower() if c.isalnum() else "-" for c in entry.label).strip("-")
            log_file = self.log_root / f"{log_slug}.log"
            events = self._parse_stage_timeline(log_file)
            if events:
                stages_with_timelines.append((entry, events))
        if stages_with_timelines:
            print("  Per-stage event timelines  (» marks steps ≥5% of stage time):")
            print(thin)
            for entry, events in stages_with_timelines:
                print(f"   {entry.label}  ({format_elapsed(entry.elapsed_seconds)})")
                # Pair each event with delta-to-next so readers can see which
                # step consumed the most time within the stage.
                for idx, (offset, message) in enumerate(events):
                    next_offset = events[idx + 1][0] if idx + 1 < len(events) else (entry.elapsed_seconds or offset)
                    step_elapsed = max(0.0, next_offset - offset)
                    compact_message = message if len(message) <= 58 else message[:55] + "…"
                    marker = "»" if entry.elapsed_seconds and step_elapsed / entry.elapsed_seconds >= 0.05 else " "
                    print(
                        f"     {marker} +{offset:>5.1f}s   "
                        f"{format_elapsed(step_elapsed):>7s}   "
                        f"{compact_message}"
                    )
                print()
        else:
            print("  Per-stage event timelines: (no shell-harness events to report)")
            print()
        print(bar)
