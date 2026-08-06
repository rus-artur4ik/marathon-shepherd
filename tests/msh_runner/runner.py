"""The TestRunner: owns the run's state and drives it from configuration to summary."""

from __future__ import annotations

import os
import shutil
import signal
import sys
import tempfile
from pathlib import Path
from typing import Sequence

from .environment import rich_available
from .execution import ExecutionMixin
from .gradle import build_gradle_stage_args, is_plain_mode
from .options import RunnerOptions
from .preflight import PreflightMixin
from .printers import PlainPrinter, RichPrinter
from .reporting import ReportingMixin
from .stage_state import StageStateMixin
from .status import StatusEntry


class TestRunner(StageStateMixin, PreflightMixin, ExecutionMixin, ReportingMixin):
    """Drives one test run from configuration to summary.

    Behaviour is grouped into mixins by concern (stage state, preflight, execution,
    reporting); all run state is owned here and initialised in __init__.
    """

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

    def seed_stages(self, selected_stage_labels: Sequence[str]) -> None:
        self.seed_stage("Runner configuration")
        self.seed_stage("Checking prerequisites")
        for stage_label in selected_stage_labels:
            self.seed_stage(stage_label)
        self.seed_stage("Final summary")

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
