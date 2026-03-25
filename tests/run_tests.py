#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence
from urllib.error import URLError
from urllib.request import urlopen

REPO_ROOT: Path = Path(__file__).resolve().parent.parent
LOG_TAIL_LINES: int = 15
FAILURE_LOG_TAIL_LINES: int = 60
DYNAMIC_REFRESH_SECONDS: float = 0.2
SPINNER_FRAMES: tuple[str, ...] = ("|", "/", "-", "\\")

# Matches one "completed task" line in any stage log.
# • ^> Task :   — Gradle task started/completed (build & unit stages)
# • \[OK\]      — Groovy unit test result
# • \[FAIL\]    — Groovy unit test failure
# • \bPASS\b    — e2e scenario passed  (standalone word, not "PASSED")
# • \bFAIL\b    — e2e scenario failed  (standalone word, not "FAILED")
_STAGE_TASK_RE = re.compile(r"^> Task :|\[OK\]|\[FAIL\]|\bPASS\b|\bFAIL\b")


@dataclass
class RunnerOptions:
    run_component_cli: bool = True     # [component:cli]  — local services via CLI, no Docker needed
    run_component_docker: bool = True  # [component:docker] — per-service Docker isolation tests
    run_docker_integration: bool = True
    run_e2e_docker: bool = True
    gradle_continue_enabled: bool = True
    gradle_test_args: list[str] = field(default_factory=list)


@dataclass
class StatusEntry:
    label: str
    status: str
    detail: str = ""


def usage() -> str:
    return """Usage: tests/run_tests.sh [options] [-- <gradle test args>]

Test groups and subjects:
  [build]            installDist per service (compile artifacts, required by later stages)
  [unit]             Isolated JVM tests per service + Jenkins shared-library unit tests
  [component:cli]    All services run locally via CLI, tested over HTTP — no Docker needed
  [component:docker] Each service built and run in Docker isolation, tested over HTTP
  [integration]      Full Docker Compose stack, synthetic devices
  [e2e]              Scenario-based Docker suite (manager + all adapters + harnesses)

All stages run by default. Use flags to skip stages you don't need:

  --skip-component-cli    Skip [component:cli] local-services stage
  --skip-component-docker Skip [component:docker] per-service Docker stages
  --skip-component-tests  Skip both component:cli and component:docker
  --skip-docker-integration   Skip [integration] docker_compose.sh
  --skip-e2e-docker           Skip [e2e] docker scenarios
  --skip-environment-integration  Skip all Docker-dependent stages
                                  (component:docker + integration + e2e)
  --fail-fast, --no-continue  Stop on first failed stage (default: continue)
  -h, --help                  Show this help

Examples:
  tests/run_tests.sh
  tests/run_tests.sh --skip-component-docker --skip-e2e-docker
  tests/run_tests.sh --skip-environment-integration -- --console=plain
  tests/run_tests.sh --fail-fast
"""


def parse_args(argv: Sequence[str]) -> RunnerOptions:
    options = RunnerOptions()
    args = deque(argv)
    pass_through = False
    while args:
        arg = args.popleft()
        if pass_through:
            options.gradle_test_args.append(arg)
            continue
        if arg == "--":
            pass_through = True
            continue
        if arg == "--skip-component-cli":
            options.run_component_cli = False
            continue
        if arg in ("--skip-component-docker", "--skip-smoke-docker"):
            options.run_component_docker = False
            continue
        if arg in ("--skip-component-tests",):
            options.run_component_cli = False
            options.run_component_docker = False
            continue
        if arg in ("--skip-docker-integration", "--skip-docker"):
            options.run_docker_integration = False
            continue
        if arg in ("--skip-e2e-docker", "--skip-e2e"):
            options.run_e2e_docker = False
            continue
        if arg in ("--skip-environment-integration", "--skip-env-integration"):
            options.run_component_docker = False
            options.run_docker_integration = False
            options.run_e2e_docker = False
            continue
        if arg in ("--fail-fast", "--no-continue"):
            options.gradle_continue_enabled = False
            continue
        if arg in ("-h", "--help"):
            print(usage(), end="")
            raise SystemExit(0)
        options.gradle_test_args.append(arg)
    return options


def current_timestamp() -> str:
    return time.strftime("%H:%M:%S")


def is_plain_mode(gradle_args: Sequence[str]) -> bool:
    if not sys.stdout.isatty():
        return True
    return any(
        arg == "--console=plain" or arg == "-Dorg.gradle.console=plain"
        for arg in gradle_args
    )


def rich_available() -> bool:
    return importlib.util.find_spec("rich") is not None


def remove_gradle_arg(gradle_args: Sequence[str], prefixes: tuple[str, ...]) -> list[str]:
    result: list[str] = []
    for arg in gradle_args:
        if arg.startswith(prefixes):
            continue
        result.append(arg)
    return result


def build_gradle_stage_args(
    gradle_args: Sequence[str],
    continue_enabled: bool,
    use_plain_logs: bool,
) -> list[str]:
    has_console_arg = any(
        arg.startswith("--console=") or arg.startswith("-Dorg.gradle.console=")
        for arg in gradle_args
    )
    args_without_continue = [arg for arg in gradle_args if arg != "--continue"]
    args_without_console = remove_gradle_arg(
        gradle_args,
        ("--console=", "-Dorg.gradle.console="),
    )
    args_without_console_or_continue = [arg for arg in args_without_console if arg != "--continue"]
    stage_args: list[str] = []
    if continue_enabled:
        stage_args.append("--continue")
    if use_plain_logs:
        if has_console_arg:
            stage_args.extend(args_without_continue)
        else:
            stage_args.append("--console=plain")
            stage_args.extend(args_without_continue)
    else:
        stage_args.append("--console=plain")
        stage_args.extend(args_without_console_or_continue)
    return stage_args


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


def join_shell_command(command: Sequence[str]) -> str:
    return shlex.join(command)


def check_command_available(command_name: str) -> tuple[bool, str]:
    if shutil.which(command_name):
        return True, ""
    return False, "command not found"


def check_executable(path: Path) -> tuple[bool, str]:
    if path.is_file() and os.access(path, os.X_OK):
        return True, ""
    return False, f"not executable: {path}"


def check_docker_engine() -> tuple[bool, str]:
    result = subprocess.run(
        ["docker", "info"],
        cwd=REPO_ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if result.returncode == 0:
        return True, ""
    return False, "Docker Engine is not reachable"


def list_adb_devices() -> list[tuple[str, str]]:
    result = subprocess.run(
        ["adb", "devices"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return []
    devices: list[tuple[str, str]] = []
    for line in result.stdout.splitlines()[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2:
            devices.append((parts[0], parts[1]))
    return devices


def resolve_emulator_binary() -> Path | None:
    sdk_root = os.getenv("ANDROID_SDK_ROOT") or os.getenv("ANDROID_HOME") or f"{Path.home()}/Library/Android/sdk"
    candidate = Path(sdk_root) / "emulator" / "emulator"
    if candidate.is_file() and os.access(candidate, os.X_OK):
        return candidate
    emulator = shutil.which("emulator")
    if emulator:
        return Path(emulator)
    return None


def has_avd(emulator_binary: Path, avd_name: str) -> bool:
    result = subprocess.run(
        [str(emulator_binary), "-list-avds"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return result.returncode == 0 and avd_name in result.stdout.splitlines()


def http_health_ok(url: str, timeout_seconds: float = 2.0) -> bool:
    try:
        with urlopen(url, timeout=timeout_seconds) as response:
            return 200 <= response.status < 300
    except (URLError, TimeoutError, ValueError):
        return False


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
            vertical_overflow="visible",
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
                line.append(" :: ", style="dim")
                line.append(entry.detail, style="bright_white")
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
        log_lines = [shorten(line, available_width) for line in read_tail_lines(current_log_file, LOG_TAIL_LINES)]

        def build_status_text(entry: StatusEntry) -> Text:
            line = Text()
            line.append(f" {status_label(entry.status)} ", style=status_style(entry.status))
            line.append("  ")
            line.append(entry.label, style="white")
            if entry.detail:
                line.append(" :: ", style="dim")
                line.append(entry.detail, style="bright_white")
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
                line.append(" :: ", style="dim")
                line.append(entry.detail, style="bright_white")
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


class TestRunner:
    def __init__(self, options: RunnerOptions) -> None:
        self.options = options
        self.use_plain_logs = is_plain_mode(options.gradle_test_args)
        self.rich_dependency_missing = False
        if not self.use_plain_logs and not rich_available():
            self.use_plain_logs = True
            self.rich_dependency_missing = True
        self.gradle_stage_args = build_gradle_stage_args(
            options.gradle_test_args,
            options.gradle_continue_enabled,
            self.use_plain_logs,
        )
        self.keep_stage_logs = os.getenv("MSH_KEEP_STAGE_LOGS", "false").lower() == "true"
        self.run_root = Path(tempfile.mkdtemp())
        self.log_root = self.run_root / "stage-logs"
        self.log_root.mkdir(parents=True, exist_ok=True)
        self.config_lines: list[str] = []
        self.prerequisites: list[StatusEntry] = []
        self.stages: list[StatusEntry] = []
        self.current_stage_label: str | None = None
        self.current_log_file: Path | None = None
        self.current_command_text: str = "n/a"
        self.plain = PlainPrinter()
        self.rich = None if self.use_plain_logs else RichPrinter()
        self.failed = False
        self.failed_stage_logs: list[tuple[str, Path]] = []
        self.report_printed = False
        self._install_signal_handlers()

    def _install_signal_handlers(self) -> None:
        def handle_signal(signum, _frame) -> None:
            raise KeyboardInterrupt(f"Interrupted by signal {signum}")

        signal.signal(signal.SIGINT, handle_signal)
        signal.signal(signal.SIGTERM, handle_signal)

    def cleanup(self) -> None:
        if self.rich is not None and not self.report_printed:
            self.refresh()
            self.rich.stop()
            self.report_printed = True
        if self.run_root.exists() and (self.failed or self.keep_stage_logs):
            return
        if self.run_root.exists():
            shutil.rmtree(self.run_root, ignore_errors=True)

    def seed_prerequisite(self, label: str) -> None:
        self.prerequisites.append(StatusEntry(label=label, status="pending"))

    def seed_stage(self, label: str) -> None:
        self.stages.append(StatusEntry(label=label, status="pending"))

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
                return
        entries.append(StatusEntry(label=label, status=status, detail=detail))

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

    def seed_prerequisites(self) -> None:
        for label in ("java", "curl", "python3", "adb", "groovy"):
            self.seed_prerequisite(label)
        needs_docker = self.options.run_component_docker or self.options.run_docker_integration or self.options.run_e2e_docker
        if needs_docker:
            self.seed_prerequisite("docker")
            self.seed_prerequisite("docker engine")
        self.seed_prerequisite("entrypoint :: ./gradlew")
        self.seed_prerequisite("entrypoint :: component (cli)")
        self.seed_prerequisite("entrypoint :: unit (jenkins)")
        if self.options.run_component_docker:
            self.seed_prerequisite("entrypoint :: component (docker)")
        if self.options.run_docker_integration:
            self.seed_prerequisite("entrypoint :: integration (docker-compose)")
        if self.options.run_e2e_docker:
            self.seed_prerequisite("entrypoint :: e2e (docker scenarios)")

    def seed_stages(self, selected_stage_labels: Sequence[str]) -> None:
        self.seed_stage("Runner configuration")
        self.seed_stage("Checking prerequisites")
        for stage_label in selected_stage_labels:
            self.seed_stage(stage_label)
        self.seed_stage("Final summary")

    def refresh(self) -> None:
        if self.rich is not None:
            self.rich.refresh(self.config_lines, self.prerequisites, self.stages, self.current_log_file)

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
        print(f"\n{bar}")
        print(f"  FAILED: {stage_label}")
        print(thin)
        if not log_file.is_file() or log_file.stat().st_size == 0:
            print("  (no output captured)")
        else:
            lines = read_tail_lines(log_file, FAILURE_LOG_TAIL_LINES)
            # strip leading blank padding lines added by read_tail_lines
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

    def prepare_configuration(self) -> None:
        all_stage_labels = [
            "[build] all",
            "[unit] manager",
            "[unit] shepherd-adb",
            "[unit] shepherd-cuttlefish",
            "[unit] jenkins",
            "[component:cli] local services",
            "[component:docker] shepherd-adb",
            "[component:docker] shepherd-farm",
            "[component:docker] shepherd-cuttlefish",
            "[component:docker] manager",
            "[integration] docker compose",
            "[e2e] docker scenarios",
        ]
        selected_stages: list[str] = ["build", "unit"]
        if self.options.run_component_cli:
            selected_stages.append("component:cli")
        if self.options.run_component_docker:
            selected_stages.append("component:docker")
        if self.options.run_docker_integration:
            selected_stages.append("integration-docker")
        if self.options.run_e2e_docker:
            selected_stages.append("e2e-docker")
        self.config_lines.append(f"Output mode: {'plain' if self.use_plain_logs else 'dynamic (rich)'}")
        self.config_lines.append(
            f"Fail strategy: {'continue' if self.options.gradle_continue_enabled else 'fail-fast'}"
        )
        if self.rich_dependency_missing:
            self.config_lines.append("UI mode fallback: plain (Python package 'rich' is not installed)")
        self.config_lines.append("Selected stages:")
        for stage_name in selected_stages:
            self.config_lines.append(f"  - {stage_name}")
        if self.options.gradle_test_args:
            self.config_lines.append(f"Gradle passthrough args: {' '.join(self.options.gradle_test_args)}")
        else:
            self.config_lines.append("Gradle passthrough args: none")
        self.seed_prerequisites()
        self.seed_stages(all_stage_labels)

    def validate_prerequisites(self) -> None:
        ok = True
        self.current_log_file = None
        self.start_stage("Checking prerequisites", "collecting required tools and entrypoints")
        for label, command_name in (
            ("java", "java"),
            ("curl", "curl"),
            ("python3", "python3"),
            ("adb", "adb"),
            ("groovy", "groovy"),
        ):
            available, detail = check_command_available(command_name)
            self.set_prerequisite(label, "ok" if available else "missing", detail)
            ok = ok and available
        needs_docker = self.options.run_component_docker or self.options.run_docker_integration or self.options.run_e2e_docker
        if needs_docker:
            available, detail = check_command_available("docker")
            self.set_prerequisite("docker", "ok" if available else "missing", detail)
            ok = ok and available
            if available:
                engine_ok, engine_detail = check_docker_engine()
                self.set_prerequisite("docker engine", "ok" if engine_ok else "missing", engine_detail)
                ok = ok and engine_ok
            else:
                self.set_prerequisite("docker engine", "missing", "docker is not installed")
        entrypoints = [
            ("entrypoint :: ./gradlew", REPO_ROOT / "gradlew"),
            ("entrypoint :: component (cli)", REPO_ROOT / "tests/component/cli/console.sh"),
            ("entrypoint :: unit (jenkins)", REPO_ROOT / "tests/unit/jenkins_unit_test.groovy"),
        ]
        if self.options.run_component_docker:
            entrypoints.append(("entrypoint :: component (docker)", REPO_ROOT / "tests/component/docker/smoke_docker.sh"))
        if self.options.run_docker_integration:
            entrypoints.append(("entrypoint :: integration (docker-compose)", REPO_ROOT / "tests/integration/docker_compose.sh"))
        if self.options.run_e2e_docker:
            entrypoints.append(("entrypoint :: e2e (docker scenarios)", REPO_ROOT / "tests/e2e/docker/e2e_docker.sh"))
        for label, path in entrypoints:
            available, detail = check_executable(path)
            self.set_prerequisite(label, "ok" if available else "missing", detail)
            ok = ok and available
        self.update_stage("ok" if ok else "failed", "all selected prerequisites are available" if ok else "missing prerequisites")
        if self.use_plain_logs:
            self.print_plain_prerequisites()
        if not ok:
            self.failed = True
            raise RuntimeError("Install missing prerequisites or skip the corresponding stage")

    def run(self) -> int:
        try:
            self.prepare_configuration()
            self.start_stage("Runner configuration", "collecting selected mode and stages")
            self.update_stage("ok", "configuration captured")
            if self.use_plain_logs:
                self.print_plain_configuration()
                if self.rich_dependency_missing:
                    self.plain.print_warn(
                        "Install the local runner dependency for the Python TTY dashboard: "
                        "python3 -m venv .msh-runner-venv && "
                        ".msh-runner-venv/bin/python -m pip install -r tests/requirements_runner.txt"
                    )
            self.validate_prerequisites()
            self._run_all_stages()
            return 0
        except KeyboardInterrupt as exc:
            self.failed = True
            message = str(exc) or "Interrupted"
            if self.use_plain_logs:
                self.plain.print_error(message)
            else:
                self.rich.stop()  # type: ignore[union-attr]
                print(f"ERROR: {message}", file=sys.stderr)
                self.report_printed = True
            return 130
        except RuntimeError as exc:
            self.failed = True
            if self.use_plain_logs:
                self.plain.print_error(str(exc))
            else:
                if self.rich is not None and not self.report_printed:
                    self.refresh()
                    self.rich.stop()
                    self.report_printed = True
                print(f"ERROR: {exc}", file=sys.stderr)
            return 1
        finally:
            self.cleanup()

    def _run_combined_stage(
        self,
        label: str,
        sub_commands: list[tuple[str, Sequence[str], dict[str, str] | None]],
        success_message: str,
    ) -> None:
        """Run several commands sequentially under a single stage entry.

        Progress is shown as ``x/y (z%)`` where x = builds completed and y = total,
        rather than counting individual Gradle tasks.
        """
        total = len(sub_commands)
        log_slug = "".join(c.lower() if c.isalnum() else "-" for c in label).strip("-")
        log_file = self.log_root / f"{log_slug}.log"
        self.current_log_file = log_file
        self.current_command_text = label
        log_file.write_text("", encoding="utf-8")  # clear / create combined log
        self.start_stage(label, f"0/{total} (0%) · starting")

        if self.use_plain_logs:
            self.plain.print_step(label)

        for idx, (sub_label, command, env) in enumerate(sub_commands):
            # Append a section divider to the shared log so failures are navigable
            with log_file.open("a", encoding="utf-8") as lh:
                lh.write(f"\n{'─' * 60}\n[{idx + 1}/{total}] {sub_label}\n{'─' * 60}\n")

            if self.use_plain_logs:
                self.plain.print_info(f"[{idx + 1}/{total}] {sub_label}")
                with log_file.open("a", encoding="utf-8") as log_handle:
                    process = subprocess.Popen(
                        list(command),
                        cwd=REPO_ROOT,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT,
                        text=True,
                        bufsize=1,
                        env=env,
                    )
                    assert process.stdout is not None
                    for line in process.stdout:
                        print(line, end="")
                        log_handle.write(line)
                    exit_code = process.wait()
            else:
                start_time = time.monotonic()
                with log_file.open("a", encoding="utf-8") as log_handle:
                    process = subprocess.Popen(
                        list(command),
                        cwd=REPO_ROOT,
                        stdout=log_handle,
                        stderr=subprocess.STDOUT,
                        text=True,
                        env=env,
                    )
                    spinner_index = 0
                    while True:
                        exit_code = process.poll()
                        elapsed = int(time.monotonic() - start_time)
                        pct = int(idx / total * 100)
                        detail = (
                            f"{idx}/{total} ({pct}%) · {sub_label} · {elapsed}s "
                            f"{SPINNER_FRAMES[spinner_index]}"
                        )
                        self.update_running_stage(detail)
                        if exit_code is not None:
                            break
                        spinner_index = (spinner_index + 1) % len(SPINNER_FRAMES)
                        time.sleep(DYNAMIC_REFRESH_SECONDS)

            if exit_code != 0:
                self.update_stage("failed", f"exit code {exit_code} · {sub_label}")
                self.failed = True
                self.failed_stage_logs.append((label, log_file))
                if not self.options.gradle_continue_enabled:
                    if not self.use_plain_logs and self.rich is not None and not self.report_printed:
                        elapsed = time.monotonic() - getattr(self, "_start_time", time.monotonic())
                        self.rich.finish(False, self.stages, elapsed, self.failed_stage_logs)
                        self.report_printed = True
                    self.print_failure_report()
                    raise RuntimeError(f"{label} failed at: {sub_label}")
                return

            completed = idx + 1
            pct = int(completed / total * 100)
            if self.use_plain_logs:
                self.plain.print_info(f"  ✓ {sub_label}")
            else:
                self.update_running_stage(f"{completed}/{total} ({pct}%) · {sub_label} done")

        self.update_stage("ok", success_message)
        if self.use_plain_logs:
            self.plain.print_info(success_message)

    def _run_all_stages(self) -> None:
        self._start_time = time.monotonic()
        repo_env = os.environ.copy()
        if self.use_plain_logs:
            repo_env["NO_COLOR"] = "1"
        # ── [build] all services (combined) ───────────────────────────────────
        self._run_combined_stage(
            "[build] all",
            [
                (
                    service,
                    [str(REPO_ROOT / "gradlew"), *self.gradle_stage_args, task],
                    repo_env,
                )
                for service, task in (
                    ("manager", ":manager:service:installDist"),
                    ("shepherd-adb", ":adapter:shepherd-adb:installDist"),
                    ("shepherd-farm", ":adapter:shepherd-farm:installDist"),
                    ("shepherd-cuttlefish", ":adapter:shepherd-cuttlefish:installDist"),
                )
            ],
            "all services built",
        )
        # ── [unit] per service ────────────────────────────────────────────────
        for service, task in (
            ("manager", ":manager:service:test"),
            ("shepherd-adb", ":adapter:shepherd-adb:test"),
            ("shepherd-cuttlefish", ":adapter:shepherd-cuttlefish:test"),
        ):
            self._run_stage_command(
                f"[unit] {service}",
                f"{service} unit tests passed",
                [str(REPO_ROOT / "gradlew"), *self.gradle_stage_args, task],
                env=repo_env,
            )
        self._run_stage_command(
            "[unit] jenkins",
            "Jenkins shared-library unit tests passed",
            ["groovy", str(REPO_ROOT / "tests/unit/jenkins_unit_test.groovy")],
            env=repo_env,
        )
        integration_env = os.environ.copy()
        integration_env["NO_COLOR"] = "1"
        integration_env["MSH_LOG_MODE"] = "plain"
        # ── [component:cli] local services ────────────────────────────────────
        if self.options.run_component_cli:
            self._run_stage_command(
                "[component:cli] local services",
                "Local service CLI component scenarios passed",
                [str(REPO_ROOT / "tests/component/cli/console.sh")],
                env=integration_env,
            )
        else:
            self._skip_stage(
                "[component:cli] local services",
                "Skipped via --skip-component-cli or --skip-component-tests",
            )
        # ── [component:docker] per service ────────────────────────────────────
        for service in ("shepherd-adb", "shepherd-farm", "shepherd-cuttlefish", "manager"):
            if self.options.run_component_docker:
                svc_env = {**integration_env, "MSH_COMPONENT_SERVICE": service}
                self._run_stage_command(
                    f"[component:docker] {service}",
                    f"{service} Docker component tests passed",
                    [str(REPO_ROOT / "tests/component/docker/smoke_docker.sh")],
                    env=svc_env,
                )
            else:
                self._skip_stage(
                    f"[component:docker] {service}",
                    "Skipped via --skip-component-docker or --skip-environment-integration",
                )
        # ── [integration] ─────────────────────────────────────────────────────
        if self.options.run_docker_integration:
            self._run_stage_command(
                "[integration] docker compose",
                "Docker Compose integration scenarios passed",
                [str(REPO_ROOT / "tests/integration/docker_compose.sh")],
                env=integration_env,
            )
        else:
            self._skip_stage(
                "[integration] docker compose",
                "Skipped via --skip-docker-integration or --skip-environment-integration",
            )
        # ── [e2e] ─────────────────────────────────────────────────────────────
        if self.options.run_e2e_docker:
            self._run_stage_command(
                "[e2e] docker scenarios",
                "All Docker-based e2e cluster scenarios passed",
                [str(REPO_ROOT / "tests/e2e/docker/e2e_docker.sh")],
                env=integration_env,
            )
        else:
            self._skip_stage(
                "[e2e] docker scenarios",
                "Skipped via --skip-e2e-docker or --skip-environment-integration",
            )
        elapsed = time.monotonic() - self._start_time
        if self.failed:
            count = len(self.failed_stage_logs)
            failed_names = ", ".join(label for label, _ in self.failed_stage_logs)
            summary_msg = f"{count} stage(s) failed: {failed_names}"
            self.start_stage("Final summary", "collecting final result")
            self.update_stage("failed", summary_msg)
            if not self.use_plain_logs and self.rich is not None and not self.report_printed:
                self.rich.finish(False, self.stages, elapsed, self.failed_stage_logs)
                self.report_printed = True
            if self.use_plain_logs:
                self.plain.print_step("Final summary")
                self.plain.print_info(summary_msg)
            self.print_failure_report()
            raise RuntimeError(summary_msg)
        else:
            self.start_stage("Final summary", "collecting final result")
            self.update_stage("ok", "All selected test stages passed")
            if not self.use_plain_logs and self.rich is not None and not self.report_printed:
                self.rich.finish(True, self.stages, elapsed, self.failed_stage_logs)
                self.report_printed = True
            if self.use_plain_logs:
                self.plain.print_step("Final summary")
                self.plain.print_info("All selected test stages passed")

    def _skip_stage(self, label: str, reason: str) -> None:
        self.current_log_file = None
        self.start_stage(label, "skipped")
        self.update_stage("skip", reason)
        if self.use_plain_logs:
            self.plain.print_step(label)
            self.plain.print_warn(reason)

    def _count_gradle_tasks_dry_run(
        self,
        command: Sequence[str],
        env: dict[str, str] | None,
    ) -> int | None:
        """Return expected task count via --dry-run for Gradle commands, else None."""
        if not command or not str(command[0]).endswith("gradlew"):
            return None
        dry_run_cmd = list(command) + ["--dry-run"]
        try:
            result = subprocess.run(
                dry_run_cmd,
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                timeout=30,
                env=env,
            )
            count = sum(1 for line in result.stdout.splitlines() if line.startswith("> Task :"))
            return count if count > 0 else None
        except Exception:
            return None

    def _run_stage_command(
        self,
        label: str,
        success_message: str,
        command: Sequence[str],
        env: dict[str, str] | None = None,
    ) -> None:
        log_slug = "".join(char.lower() if char.isalnum() else "-" for char in label).strip("-")
        log_file = self.log_root / f"{log_slug}.log"
        self.current_log_file = log_file
        self.current_command_text = join_shell_command(command)
        self.start_stage(label, "starting")
        if self.use_plain_logs:
            self.plain.print_step(label)
            exit_code = self._run_plain_command(command, log_file, env)
            if exit_code != 0:
                self.update_stage("failed", f"exit code {exit_code}")
                self.failed = True
                self.failed_stage_logs.append((label, log_file))
                if not self.options.gradle_continue_enabled:
                    self.print_failure_report()
                    raise RuntimeError(f"{label} failed")
                return
            self.update_stage("ok", success_message)
            self.plain.print_info(success_message)
            return
        expected_tasks = self._count_gradle_tasks_dry_run(command, env)
        exit_code = self._run_dynamic_command(label, command, log_file, env, expected_tasks)
        if exit_code != 0:
            self.update_stage("failed", f"exit code {exit_code}")
            self.failed = True
            self.failed_stage_logs.append((label, log_file))
            if not self.options.gradle_continue_enabled:
                if self.rich is not None and not self.report_printed:
                    elapsed = time.monotonic() - getattr(self, "_start_time", time.monotonic())
                    self.rich.finish(False, self.stages, elapsed, self.failed_stage_logs)
                    self.report_printed = True
                self.print_failure_report()
                raise RuntimeError(f"{label} failed")
            return
        self.update_stage("ok", success_message)

    def _run_plain_command(
        self,
        command: Sequence[str],
        log_file: Path,
        env: dict[str, str] | None,
    ) -> int:
        with log_file.open("w", encoding="utf-8") as log_handle:
            process = subprocess.Popen(
                list(command),
                cwd=REPO_ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                env=env,
            )
            assert process.stdout is not None
            for line in process.stdout:
                print(line, end="")
                log_handle.write(line)
            return process.wait()

    def _run_dynamic_command(
        self,
        label: str,
        command: Sequence[str],
        log_file: Path,
        env: dict[str, str] | None,
        expected_tasks: int | None = None,
    ) -> int:
        start_time = time.monotonic()
        with log_file.open("w", encoding="utf-8") as log_handle:
            process = subprocess.Popen(
                list(command),
                cwd=REPO_ROOT,
                stdout=log_handle,
                stderr=subprocess.STDOUT,
                text=True,
                env=env,
            )
            spinner_index = 0
            while True:
                exit_code = process.poll()
                elapsed = int(time.monotonic() - start_time)
                task_count = count_stage_progress(log_file)
                if expected_tasks is not None and expected_tasks > 0:
                    pct = int(task_count / expected_tasks * 100)
                    detail = f"{task_count}/{expected_tasks} ({pct}%) · {elapsed}s {SPINNER_FRAMES[spinner_index]}"
                else:
                    detail = f"{task_count} tasks · {elapsed}s {SPINNER_FRAMES[spinner_index]}"
                self.update_running_stage(detail)
                if exit_code is not None:
                    return exit_code
                spinner_index = (spinner_index + 1) % len(SPINNER_FRAMES)
                time.sleep(DYNAMIC_REFRESH_SECONDS)


def main(argv: Sequence[str]) -> int:
    options = parse_args(argv)
    runner = TestRunner(options)
    return runner.run()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
