#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import os
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
FAILURE_LOG_TAIL_LINES: int = 120
DYNAMIC_REFRESH_SECONDS: float = 0.2
SPINNER_FRAMES: tuple[str, ...] = ("|", "/", "-", "\\")


@dataclass
class RunnerOptions:
    run_docker_integration: bool = True
    run_real_device_integration: bool = True
    run_public_ui_integration: bool = True
    skip_jenkins_harness: bool = False
    gradle_continue_enabled: bool = True
    gradle_test_args: list[str] = field(default_factory=list)


@dataclass
class StatusEntry:
    label: str
    status: str
    detail: str = ""


def usage() -> str:
    return """Usage: scripts/run_tests.sh [options] [-- <gradle test args>]

Options:
  --skip-docker-integration       Skip scripts/integration/docker_compose.sh
  --run-real-device-integration   Explicitly enable scripts/integration/real_device.sh
  --skip-real-device-integration  Skip scripts/integration/real_device.sh
  --skip-public-ui-integration    Skip scripts/public_ui/run_tests.sh
  --skip-environment-integration  Skip all environment-dependent integration tests (docker + real-device + public-ui)
  --skip-jenkins-harness          Skip Groovy Jenkins harness scenarios in integration scripts
  --fail-fast, --no-continue      Disable default Gradle --continue behavior
  -h, --help                      Show this help

Default stages:
  1) ./gradlew test
  2) ./gradlew :manager:service:installDist :adapter:shepherd-*:installDist
  3) scripts/integration/console.sh
  4) scripts/integration/real_device.sh
  5) scripts/public_ui/run_tests.sh
  6) scripts/integration/docker_compose.sh

Examples:
  scripts/run_tests.sh
  scripts/run_tests.sh --skip-real-device-integration
  scripts/run_tests.sh --skip-docker-integration
  scripts/run_tests.sh --skip-environment-integration -- --console=plain
  scripts/run_tests.sh --fail-fast
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
        if arg in ("--skip-docker-integration", "--skip-docker"):
            options.run_docker_integration = False
            continue
        if arg in ("--run-real-device-integration", "--with-real-device-integration"):
            options.run_real_device_integration = True
            continue
        if arg in ("--skip-real-device-integration", "--skip-real-device"):
            options.run_real_device_integration = False
            continue
        if arg in ("--skip-public-ui-integration", "--skip-public-ui"):
            options.run_public_ui_integration = False
            continue
        if arg in ("--skip-environment-integration", "--skip-env-integration"):
            options.run_docker_integration = False
            options.run_real_device_integration = False
            options.run_public_ui_integration = False
            continue
        if arg == "--skip-jenkins-harness":
            options.skip_jenkins_harness = True
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
            screen=False,
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

        def build_status_text(entry: StatusEntry, width: int) -> Text:
            line = Text()
            line.append(f" {status_label(entry.status)} ", style=status_style(entry.status))
            line.append("  ")
            line.append(shorten(entry.label, max(width - 14, 8)), style="white")
            if entry.detail:
                line.append(" :: ", style="dim")
                line.append(shorten(entry.detail, max(width - 22, 8)), style="bright_white")
            line.no_wrap = True
            line.overflow = "crop"
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
                self.console.print(build_status_text(entry, available_width))
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
        log_line_count = LOG_TAIL_LINES
        log_lines = [shorten(line, available_width) for line in read_tail_lines(current_log_file, log_line_count)]
        def build_status_text(entry: StatusEntry, width: int) -> Text:
            line = Text()
            line.append(f" {status_label(entry.status)} ", style=status_style(entry.status))
            line.append("  ")
            line.append(shorten(entry.label, max(width - 14, 8)), style="white")
            if entry.detail:
                line.append(" :: ", style="dim")
                line.append(shorten(entry.detail, max(width - 22, 8)), style="bright_white")
            line.no_wrap = True
            line.overflow = "crop"
            return line

        stages_heading = Text("Stages", style="bold cyan")
        divider = Text("-" * min(available_width, 72), style="bright_black")
        renderables: list[Text] = [stages_heading]
        renderables.extend(build_status_text(entry, available_width) for entry in stages)
        renderables.append(Text(""))
        renderables.append(divider)
        renderables.extend(
            Text(line, style="bright_white" if line.strip() else "dim", no_wrap=True, overflow="crop")
            for line in log_lines
        )
        return Group(*renderables)


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
        self.real_device_manager_url_override = os.getenv("MSH_REAL_DEVICE_MANAGER_URL") or os.getenv("MSH_URL") or ""
        self.real_device_device_type = os.getenv("MSH_REAL_DEVICE_TYPE", "physical")
        self.public_ui_avd_name = os.getenv("MSH_PUBLIC_UI_AVD", "Pixel_3a_API_34")
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
        for label in ("java", "curl", "python3", "adb"):
            self.seed_prerequisite(label)
        if not self.options.skip_jenkins_harness:
            self.seed_prerequisite("groovy")
        if self.options.run_docker_integration:
            self.seed_prerequisite("docker")
            self.seed_prerequisite("docker engine")
        self.seed_prerequisite("entrypoint :: ./gradlew")
        self.seed_prerequisite("entrypoint :: console integration")
        if self.options.run_real_device_integration:
            self.seed_prerequisite("entrypoint :: real-device integration")
            self.seed_prerequisite("real-device readiness")
        if self.options.run_public_ui_integration:
            self.seed_prerequisite("entrypoint :: public-ui integration")
            self.seed_prerequisite("entrypoint :: public-ui prepare")
            self.seed_prerequisite("public-ui target")
            self.seed_prerequisite("public-ui APKs")
        if self.options.run_docker_integration:
            self.seed_prerequisite("entrypoint :: docker-compose integration")

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
        print(f"\nFailed stage: {stage_label}")
        print(f"Log file: {log_file}")
        print("------------------------------------------------------------------------")
        for line in read_tail_lines(log_file, FAILURE_LOG_TAIL_LINES):
            print(line)

    def prepare_configuration(self) -> None:
        all_stage_labels = [
            "Unit tests (Gradle)",
            "Preparing manager + adapter distributions",
            "Integration tests (console)",
            "Integration tests (real device)",
            "Integration tests (public UI sample)",
            "Integration tests (docker-compose)",
        ]
        selected_stages = ["unit-tests", "distribution-build", "console-integration"]
        if self.options.run_real_device_integration:
            selected_stages.append("real-device-integration")
        if self.options.run_public_ui_integration:
            selected_stages.append("public-ui-integration")
        if self.options.run_docker_integration:
            selected_stages.append("docker-compose-integration")
        self.config_lines.append(f"Output mode: {'plain' if self.use_plain_logs else 'dynamic (rich)'}")
        self.config_lines.append(
            f"Fail strategy: {'continue' if self.options.gradle_continue_enabled else 'fail-fast'}"
        )
        if self.rich_dependency_missing:
            self.config_lines.append("UI mode fallback: plain (Python package 'rich' is not installed)")
        self.config_lines.append("Selected stages:")
        for stage_name in selected_stages:
            self.config_lines.append(f"  - {stage_name}")
        self.config_lines.append(
            f"Jenkins harness scenarios: {'skipped' if self.options.skip_jenkins_harness else 'included'}"
        )
        if self.options.run_real_device_integration:
            if self.real_device_manager_url_override:
                self.config_lines.append(f"Real-device manager: {self.real_device_manager_url_override}")
            else:
                self.config_lines.append("Real-device stack: self-managed temporary manager + shepherd-adb")
            self.config_lines.append(f"Real-device type: {self.real_device_device_type}")
        if self.options.run_public_ui_integration:
            self.config_lines.append("Public UI sample: android/architecture-samples")
            self.config_lines.append(f"Public UI AVD: {self.public_ui_avd_name}")
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
        ):
            available, detail = check_command_available(command_name)
            self.set_prerequisite(label, "ok" if available else "missing", detail)
            ok = ok and available
        if not self.options.skip_jenkins_harness:
            available, detail = check_command_available("groovy")
            self.set_prerequisite("groovy", "ok" if available else "missing", detail)
            ok = ok and available
        if self.options.run_docker_integration:
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
            ("entrypoint :: console integration", REPO_ROOT / "scripts/integration/console.sh"),
        ]
        if self.options.run_real_device_integration:
            entrypoints.append(("entrypoint :: real-device integration", REPO_ROOT / "scripts/integration/real_device.sh"))
        if self.options.run_public_ui_integration:
            entrypoints.append(("entrypoint :: public-ui integration", REPO_ROOT / "scripts/public_ui/run_tests.sh"))
            entrypoints.append(("entrypoint :: public-ui prepare", REPO_ROOT / "scripts/public_ui/build_test_apk.sh"))
        if self.options.run_docker_integration:
            entrypoints.append(("entrypoint :: docker-compose integration", REPO_ROOT / "scripts/integration/docker_compose.sh"))
        for label, path in entrypoints:
            available, detail = check_executable(path)
            self.set_prerequisite(label, "ok" if available else "missing", detail)
            ok = ok and available
        if self.options.run_real_device_integration:
            ok = self._record_real_device_prerequisite() and ok
        if self.options.run_public_ui_integration:
            ok = self._record_public_ui_prerequisite() and ok
        if self.options.skip_jenkins_harness:
            os.environ["MSH_SKIP_JENKINS_HARNESS"] = "true"
        else:
            os.environ.pop("MSH_SKIP_JENKINS_HARNESS", None)
        self.update_stage("ok" if ok else "failed", "all selected prerequisites are available" if ok else "missing prerequisites")
        if self.use_plain_logs:
            self.print_plain_prerequisites()
        if not ok:
            self.failed = True
            raise RuntimeError("Install missing prerequisites or skip the corresponding stage")

    def _record_real_device_prerequisite(self) -> bool:
        if self.real_device_manager_url_override:
            available = http_health_ok(f"{self.real_device_manager_url_override}/health")
            self.set_prerequisite(
                "real-device readiness",
                "ok" if available else "missing",
                f"external manager: {self.real_device_manager_url_override}" if available else f"unreachable external manager: {self.real_device_manager_url_override}",
            )
            return available
        if self.real_device_device_type != "physical":
            self.set_prerequisite(
                "real-device readiness",
                "missing",
                "self-managed mode supports only physical devices; set MSH_REAL_DEVICE_MANAGER_URL for external manager",
            )
            return False
        physical_devices = [serial for serial, state in list_adb_devices() if state == "device" and not serial.startswith("emulator-")]
        if not physical_devices:
            self.set_prerequisite("real-device readiness", "missing", "no connected physical adb devices")
            return False
        self.set_prerequisite("real-device readiness", "ok", ", ".join(physical_devices))
        return True

    def _record_public_ui_prerequisite(self) -> bool:
        app_apk_path = REPO_ROOT / "scripts/public_ui/apks/architecture-samples-ee66e1526b84c026615df032c705842b7d2a521f/app-debug.apk"
        test_apk_path = REPO_ROOT / "scripts/public_ui/apks/architecture-samples-ee66e1526b84c026615df032c705842b7d2a521f/app-debug-androidTest.apk"
        if not app_apk_path.is_file() or not test_apk_path.is_file():
            self.set_prerequisite(
                "public-ui APKs",
                "missing",
                "run scripts/public_ui/build_test_apk.sh",
            )
            return False
        self.set_prerequisite("public-ui APKs", "ok", str(app_apk_path.parent.relative_to(REPO_ROOT)))
        running_emulator = next(
            (serial for serial, state in list_adb_devices() if state == "device" and serial.startswith("emulator-")),
            "",
        )
        if running_emulator:
            self.set_prerequisite("public-ui target", "ok", running_emulator)
            return True
        emulator_binary = resolve_emulator_binary()
        if emulator_binary is None:
            self.set_prerequisite("public-ui target", "missing", "Android SDK emulator binary not found")
            return False
        if not has_avd(emulator_binary, self.public_ui_avd_name):
            self.set_prerequisite("public-ui target", "missing", self.public_ui_avd_name)
            return False
        self.set_prerequisite("public-ui target", "ok", self.public_ui_avd_name)
        return True

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
                        ".msh-runner-venv/bin/python -m pip install -r scripts/requirements_runner.txt"
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

    def _run_all_stages(self) -> None:
        repo_env = os.environ.copy()
        if self.use_plain_logs:
            repo_env["NO_COLOR"] = "1"
        self._run_stage_command(
            "Unit tests (Gradle)",
            "Gradle unit tests passed",
            [str(REPO_ROOT / "gradlew"), *self.gradle_stage_args, "test"],
            env=repo_env,
        )
        self._run_stage_command(
            "Preparing manager + adapter distributions",
            "Manager and adapter distributions are ready",
            [
                str(REPO_ROOT / "gradlew"),
                *self.gradle_stage_args,
                ":manager:service:installDist",
                ":adapter:shepherd-adb:installDist",
                ":adapter:shepherd-farm:installDist",
                ":adapter:shepherd-cuttlefish:installDist",
            ],
            env=repo_env,
        )
        integration_env = os.environ.copy()
        integration_env["NO_COLOR"] = "1"
        integration_env["MSH_LOG_MODE"] = "plain"
        self._run_stage_command(
            "Integration tests (console)",
            "Console integration scenarios passed",
            [str(REPO_ROOT / "scripts/integration/console.sh")],
            env=integration_env,
        )
        if self.options.run_real_device_integration:
            self._run_stage_command(
                "Integration tests (real device)",
                "Real-device integration scenarios passed",
                [str(REPO_ROOT / "scripts/integration/real_device.sh")],
                env=integration_env,
            )
        else:
            self._skip_stage(
                "Integration tests (real device)",
                "Skipped via --skip-real-device-integration or --skip-environment-integration",
            )
        if self.options.run_public_ui_integration:
            self._run_stage_command(
                "Integration tests (public UI sample)",
                "Public UI architecture-samples scenario passed",
                [str(REPO_ROOT / "scripts/public_ui/run_tests.sh"), "--plain"],
                env=integration_env,
            )
        else:
            self._skip_stage(
                "Integration tests (public UI sample)",
                "Skipped via --skip-public-ui-integration or --skip-environment-integration",
            )
        if self.options.run_docker_integration:
            self._run_stage_command(
                "Integration tests (docker-compose)",
                "Docker-compose integration scenarios passed",
                [str(REPO_ROOT / "scripts/integration/docker_compose.sh")],
                env=integration_env,
            )
        else:
            self._skip_stage(
                "Integration tests (docker-compose)",
                "Skipped via --skip-docker-integration or --skip-environment-integration",
            )
        self.start_stage("Final summary", "collecting final result")
        self.update_stage("ok", "All selected test stages passed")
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
                self.update_stage("failed", f"see {log_file}")
                self.failed = True
                self.print_failure_excerpt(label, log_file)
                raise RuntimeError(f"{label} failed")
            self.update_stage("ok", success_message)
            self.plain.print_info(success_message)
            return
        exit_code = self._run_dynamic_command(label, command, log_file, env)
        if exit_code != 0:
            self.update_stage("failed", f"see {log_file}")
            self.failed = True
            if self.rich is not None and not self.report_printed:
                self.refresh()
                self.rich.stop()
                self.report_printed = True
            self.print_failure_excerpt(label, log_file)
            raise RuntimeError(f"{label} failed")
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
                self.update_running_stage(
                    f"running ({elapsed}s, {SPINNER_FRAMES[spinner_index]})"
                )
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
