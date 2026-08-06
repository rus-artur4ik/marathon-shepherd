"""Output backends: a plain line printer for CI and a live Rich dashboard for terminals."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Sequence

from .logs import read_display_tail_lines
from .settings import LOG_TAIL_LINES
from .status import StatusEntry, current_timestamp, plain_status_label


class PlainPrinter:
    def print_step(self, title: str) -> None:
        print(f"\n[{current_timestamp()}] {title}")
        print("------------------------------------------------------------------------")

    def print_info(self, message: str) -> None:
        print(f"  -> {message}")

    def print_warn(self, message: str) -> None:
        print(f"  WARN {message}")

    def print_error(self, message: str) -> None:
        print(f"ERROR: {message}", file=sys.stderr)

    def print_status_entries(self, entries: Sequence[StatusEntry]) -> None:
        for entry in entries:
            if entry.detail:
                self.print_info(f"{plain_status_label(entry.status)} {entry.label} :: {entry.detail}")
            else:
                self.print_info(f"{plain_status_label(entry.status)} {entry.label}")


class RichPrinter:
    def __init__(self) -> None:
        from rich.console import Console
        from rich.live import Live

        self.console = Console()
        self.banner_printed = False
        self.prerequisites_heading_printed = False
        self.printed_prerequisite_labels: set[str] = set()
        self.live = Live(
            self.render_footer((), None),
            console=self.console,
            refresh_per_second=5,
            auto_refresh=False,
            transient=False,
            vertical_overflow="crop",
        )
        self.live.start()

    def stop(self) -> None:
        self.live.stop()

    def refresh(
        self,
        config_lines: Sequence[str],
        prerequisites: Sequence[StatusEntry],
        stages: Sequence[StatusEntry],
        current_log_file: Path | None,
    ) -> None:
        from rich.text import Text

        def shorten(value: str, width: int) -> str:
            if width < 8 or len(value) <= width:
                return value
            return f"{value[:width - 3]}..."

        def status_style(status: str) -> str:
            mapping = {
                "pending": "black on bright_black",
                "ok": "black on green",
                "running": "black on cyan",
                "skip": "black on yellow",
                "missing": "white on red",
                "failed": "white on red",
            }
            return mapping.get(status, "default")

        def status_label(status: str) -> str:
            mapping = {
                "pending": "WAIT",
                "ok": "OK",
                "running": "RUN",
                "skip": "SKIP",
                "missing": "FAIL",
                "failed": "FAIL",
            }
            return mapping.get(status, "..")

        def build_status_text(entry: StatusEntry) -> Text:
            line = Text()
            line.append(f" {status_label(entry.status)} ", style=status_style(entry.status))
            line.append("  ")
            line.append(entry.label, style="white")
            if entry.detail:
                # Success timings are purely informational — render them dim so
                # the OK glyph carries the signal; keep error/skip reasons bright
                # because the reader needs to read them.
                detail_style = "dim" if entry.status == "ok" else "bright_white"
                line.append(" :: ", style="dim")
                line.append(entry.detail, style=detail_style)
            return line

        def build_heading(title: str, meta: str = "") -> Text:
            heading = Text(title, style="bold cyan")
            if meta:
                heading.append("  ")
                heading.append(meta, style="dim")
            return heading

        available_width = max(self.console.size.width, 40)
        if not self.banner_printed:
            title = Text()
            title.append("Marathon Shepherd", style="bold bright_white")
            title.append("  ")
            title.append("test runner", style="cyan")
            divider = Text("=" * min(available_width, 72), style="bright_black")
            configuration_heading = build_heading("Configuration")
            self.console.print(title)
            self.console.print(divider)
            self.console.print(configuration_heading)
            for line in config_lines:
                self.console.print(
                    Text(
                        f"  {shorten(line, available_width)}",
                        style="white",
                        no_wrap=True,
                        overflow="crop",
                    )
                )
            self.console.print()
            self.banner_printed = True

        resolved_prerequisites = [
            entry
            for entry in prerequisites
            if entry.status != "pending" and entry.label not in self.printed_prerequisite_labels
        ]
        if resolved_prerequisites:
            if not self.prerequisites_heading_printed:
                self.console.print(build_heading("Prerequisites"))
                self.prerequisites_heading_printed = True
            for entry in resolved_prerequisites:
                self.console.print(build_status_text(entry))
                self.printed_prerequisite_labels.add(entry.label)

        self.live.update(self.render_footer(stages, current_log_file), refresh=True)

    def render_footer(
        self,
        stages: Sequence[StatusEntry],
        current_log_file: Path | None,
    ):
        from rich.text import Text
        from rich.console import Group

        def shorten(value: str, width: int) -> str:
            if width < 8 or len(value) <= width:
                return value
            return f"{value[:width - 3]}..."

        def status_style(status: str) -> str:
            mapping = {
                "pending": "black on bright_black",
                "ok": "black on green",
                "running": "black on cyan",
                "skip": "black on yellow",
                "missing": "white on red",
                "failed": "white on red",
            }
            return mapping.get(status, "default")

        def status_label(status: str) -> str:
            mapping = {
                "pending": "WAIT",
                "ok": "OK",
                "running": "RUN",
                "skip": "SKIP",
                "missing": "FAIL",
                "failed": "FAIL",
            }
            return mapping.get(status, "..")

        available_width = max(self.console.size.width, 40)
        log_lines = [shorten(line, available_width) for line in read_display_tail_lines(current_log_file, LOG_TAIL_LINES)]

        def build_status_text(entry: StatusEntry) -> Text:
            line = Text()
            line.append(f" {status_label(entry.status)} ", style=status_style(entry.status))
            line.append("  ")
            line.append(entry.label, style="white")
            if entry.detail:
                # Success timings are purely informational — render them dim so
                # the OK glyph carries the signal; keep error/skip reasons bright
                # because the reader needs to read them.
                detail_style = "dim" if entry.status == "ok" else "bright_white"
                line.append(" :: ", style="dim")
                line.append(entry.detail, style=detail_style)
            return line

        divider = Text("-" * min(available_width, 72), style="bright_black")
        renderables: list[Text] = [Text("Stages", style="bold cyan")]
        renderables.extend(build_status_text(entry) for entry in stages)
        renderables.append(Text(""))
        renderables.append(divider)
        if log_lines:
            log_header = Text()
            log_header.append("Output", style="bold cyan")
            if current_log_file is not None:
                log_header.append(f"  {current_log_file.name}", style="dim")
            renderables.append(log_header)
            renderables.extend(
                Text(line, style="bright_white" if line.strip() else "dim", no_wrap=True, overflow="crop")
                for line in log_lines
            )
        else:
            renderables.append(Text("  (waiting for output…)", style="dim"))
        return Group(*renderables)

    def render_footer_final(
        self,
        stages: Sequence[StatusEntry],
        success: bool,
        elapsed_s: float,
        failed_stage_logs: "list[tuple[str, Path]]",
    ):
        from rich.text import Text
        from rich.console import Group

        def status_style(status: str) -> str:
            mapping = {
                "pending": "black on bright_black",
                "ok": "black on green",
                "running": "black on cyan",
                "skip": "black on yellow",
                "missing": "white on red",
                "failed": "white on red",
            }
            return mapping.get(status, "default")

        def status_label(status: str) -> str:
            mapping = {
                "pending": "WAIT",
                "ok": "OK",
                "running": "RUN",
                "skip": "SKIP",
                "missing": "FAIL",
                "failed": "FAIL",
            }
            return mapping.get(status, "..")

        def build_status_text(entry: StatusEntry) -> Text:
            line = Text()
            line.append(f" {status_label(entry.status)} ", style=status_style(entry.status))
            line.append("  ")
            line.append(entry.label, style="white")
            if entry.detail:
                # Success timings are purely informational — render them dim so
                # the OK glyph carries the signal; keep error/skip reasons bright
                # because the reader needs to read them.
                detail_style = "dim" if entry.status == "ok" else "bright_white"
                line.append(" :: ", style="dim")
                line.append(entry.detail, style=detail_style)
            return line

        def fmt_elapsed(s: float) -> str:
            total = int(s)
            if total < 60:
                return f"{total}s"
            m, sec = divmod(total, 60)
            return f"{m}m {sec:02d}s"

        available_width = max(self.console.size.width, 40)
        divider = Text("-" * min(available_width, 72), style="bright_black")

        renderables: list[Text] = [Text("Stages", style="bold cyan")]
        renderables.extend(build_status_text(entry) for entry in stages)
        renderables.append(Text(""))
        renderables.append(divider)

        result_heading = Text()
        result_heading.append("Result", style="bold cyan")
        renderables.append(result_heading)

        if success:
            ok_count = sum(1 for e in stages if e.status == "ok")
            line = Text()
            line.append("  ✓ ", style="bold green")
            line.append(f"All {ok_count} stage(s) passed", style="green")
            line.append(f"  ·  {fmt_elapsed(elapsed_s)}", style="dim")
            renderables.append(line)
        else:
            failed_count = len(failed_stage_logs)
            line = Text()
            line.append("  ✗ ", style="bold red")
            line.append(f"{failed_count} stage(s) failed: ", style="red")
            line.append(", ".join(label for label, _ in failed_stage_logs), style="bright_red")
            renderables.append(line)
            elapsed_line = Text()
            elapsed_line.append(f"  elapsed: {fmt_elapsed(elapsed_s)}", style="dim")
            renderables.append(elapsed_line)

        return Group(*renderables)

    def finish(
        self,
        success: bool,
        stages: Sequence[StatusEntry],
        elapsed_s: float,
        failed_stage_logs: "list[tuple[str, Path]]",
    ) -> None:
        self.live.update(self.render_footer_final(stages, success, elapsed_s, failed_stage_logs))
        self.live.stop()
