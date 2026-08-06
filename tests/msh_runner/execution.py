"""Execution behaviour of the TestRunner."""

from __future__ import annotations

import os
import shutil
import subprocess
import time
from pathlib import Path
from threading import Lock
from typing import Sequence

from .gradle import join_shell_command
from .logs import count_stage_progress, read_stage_runtime_markers
from .settings import DYNAMIC_REFRESH_SECONDS, REPO_ROOT, SPINNER_FRAMES
from .stages import LABEL_TO_ID


class ExecutionMixin:
    """Running stages: selection, sequential and parallel execution, and process handling.

    State lives on TestRunner; this mixin only groups behaviour by concern.
    """

    def _run_combined_stage(
        self,
        label: str,
        sub_commands: list[tuple[str, Sequence[str], dict[str, str] | None]],
        success_message: str,
        phase_name: str,
    ) -> None:
        total = len(sub_commands)
        log_slug = "".join(c.lower() if c.isalnum() else "-" for c in label).strip("-")
        log_file = self.log_root / f"{log_slug}.log"
        self.current_log_file = log_file
        self.current_command_text = label
        log_file.write_text("", encoding="utf-8")  # clear / create combined log
        initial_step = 1 if total > 0 else 0
        self.start_stage(label, f"{phase_name} [{initial_step}/{total}]")

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
                        current_step = min(idx + 1, total)
                        detail = f"{phase_name} [{current_step}/{total}] · {elapsed}s {SPINNER_FRAMES[spinner_index]}"
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
            if self.use_plain_logs:
                self.plain.print_info(f"  ✓ {sub_label}")
            else:
                self.update_running_stage(f"{phase_name} [{completed}/{total}] · {sub_label} done")

        self.update_stage("ok", success_message)
        if self.use_plain_logs:
            self.plain.print_info(success_message)

    def _run_all_stages(self) -> None:
        self._start_time = time.monotonic()
        repo_env = os.environ.copy()
        if self.use_plain_logs:
            repo_env["NO_COLOR"] = "1"
        install_dist_tasks: list[str] = [
            ":manager:service:installDist",
            ":manager:cli:installDist",
            ":adapter:shepherd-adb:installDist",
            ":adapter:shepherd-farm:installDist",
            ":adapter:shepherd-cuttlefish:installDist",
        ]
        kotlin_unit_tasks: list[str] = [
            ":manager:service:test",
            ":adapter:shepherd-adb:test",
            ":adapter:shepherd-cuttlefish:test",
        ]
        # ── [build] all services (single parallel Gradle invocation) ──────────
        # Gradle's `--parallel` + `org.gradle.parallel=true` run independent
        # installDist tasks concurrently; one subprocess beats five on a warm
        # daemon because each launch paid ~1.5s of JVM/plugin init.
        self._run_stage_command(
            "[build] all",
            "all services built",
            [str(REPO_ROOT / "gradlew"), *self.gradle_stage_args, *install_dist_tasks],
            env=repo_env,
            default_phase="building",
        )
        integration_env = os.environ.copy()
        integration_env["NO_COLOR"] = "1"
        integration_env["MSH_LOG_MODE"] = "plain"
        # ── Parallel group: [unit] kotlin + [unit] jenkins + [build] docker images
        # These three are mutually independent:
        #   - kotlin uses Gradle workers (CPU-bound)
        #   - jenkins runs Groovy (separate JVM)
        #   - docker images hits the daemon (I/O + network)
        # On a warm laptop this group finishes in max(gradle, groovy, docker)
        # time instead of their sum — typically 40–60% faster than sequential.
        needs_docker_images = (
            self.options.run_component_docker
            or self.options.run_docker_integration
            or self.options.run_e2e_docker
            or self.options.run_scale
        )
        parallel_specs: list[tuple[str, str, Sequence[str], dict[str, str] | None]] = [
            (
                "[unit] kotlin",
                "manager/shepherd-adb/shepherd-cuttlefish unit tests passed",
                [str(REPO_ROOT / "gradlew"), *self.gradle_stage_args, *kotlin_unit_tasks],
                repo_env,
            ),
            (
                "[unit] jenkins",
                "Jenkins shared-library unit tests passed",
                ["groovy", str(REPO_ROOT / "tests/unit/jenkins_unit_test.groovy")],
                repo_env,
            ),
        ]
        if needs_docker_images:
            # `--refresh-cache` is plumbed as MSH_FORCE_CONFIG_REFRESH=1 to the
            # prebuild script, which then ignores the cached orchestrator
            # config TTL and re-downloads from Artifact Registry.
            prebuild_env = {
                **repo_env,
                "MSH_FORCE_CONFIG_REFRESH": "1" if self.options.refresh_cache else "0",
            }
            if self.options.refresh_cache:
                # Drop the on-disk prebuild cache too so a partial run can't
                # leave behind a stale file that's newer than the TTL cutoff.
                cache_dir = REPO_ROOT / ".msh-sandbox/prebuild-cache"
                if cache_dir.exists():
                    shutil.rmtree(cache_dir, ignore_errors=True)
            parallel_specs.append((
                "[build] docker images",
                "All docker images pre-built (cached for downstream stages)",
                [str(REPO_ROOT / "tests/helpers/docker_prebuild.sh")],
                prebuild_env,
            ))
            integration_env["MSH_SKIP_DOCKER_BUILD"] = "1"
        self._run_parallel_stages(parallel_specs)
        if needs_docker_images:
            # The prebuild stage writes the cached orchestrator config path to
            # its stage log; propagate it so downstream stages skip the
            # Artifact Registry download.
            prebuild_log = self.log_root / "build--docker-images.log"
            config_path = self._extract_marker(prebuild_log, "MSH_PREBUILT_ORCHESTRATOR_CONFIG=")
            if config_path:
                integration_env["MSH_PREBUILT_ORCHESTRATOR_CONFIG"] = config_path
        if not needs_docker_images:
            self._skip_stage(
                "[build] docker images",
                "No Docker stages selected — skipping image pre-build",
            )
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
        # ── [integration] → [e2e] stack handoff ─────────────────────────────
        # `[integration]` and `[e2e] docker scenarios` use the SAME compose
        # file (tests/integration/docker-compose.integration.yml) and the same
        # `msh-integration` project. When both stages run in sequence we ask
        # integration to LEAVE the stack running on success (via
        # MSH_LEAVE_STACK_RUNNING=1) and tell e2e to REUSE it
        # (MSH_REUSE_STACK=1). This skips a full `compose down → up → wait
        # healthy` cycle between the two stages — ~15–20s saved.
        #
        # Safety:
        #  - If integration fails, its cleanup always tears down so e2e
        #    starts fresh with diagnostics intact.
        #  - If the handed-off stack is not actually healthy when e2e looks,
        #    e2e falls back to its own `up` (see docker_stack_verify_alive
        #    in tests/helpers/docker_stack.sh).
        integration_will_run, _ = self._stage_enabled_by_id("integration:docker-compose")
        e2e_will_run, _ = self._stage_enabled_by_id("e2e:docker-scenarios")
        handoff_planned = (
            self.options.run_docker_integration
            and self.options.run_e2e_docker
            and integration_will_run
            and e2e_will_run
        )
        if self.options.run_docker_integration:
            stage_env = {**integration_env, **({"MSH_LEAVE_STACK_RUNNING": "1"} if handoff_planned else {})}
            self._run_stage_command(
                "[integration] docker compose",
                "Docker Compose integration scenarios passed",
                [str(REPO_ROOT / "tests/integration/docker_compose.sh")],
                env=stage_env,
            )
        else:
            self._skip_stage(
                "[integration] docker compose",
                "Skipped via --skip-docker-integration or --skip-environment-integration",
            )
        if self.options.run_e2e_docker:
            integration_entry = next(
                (entry for entry in self.stages if entry.label == "[integration] docker compose"),
                None,
            )
            integration_succeeded = integration_entry is not None and integration_entry.status == "ok"
            reuse_stack = handoff_planned and integration_succeeded
            stage_env = {**integration_env, **({"MSH_REUSE_STACK": "1"} if reuse_stack else {})}
            self._run_stage_command(
                "[e2e] docker scenarios",
                "All Docker-based e2e cluster scenarios passed",
                [str(REPO_ROOT / "tests/e2e/docker/e2e_docker.sh")],
                env=stage_env,
            )
        else:
            self._skip_stage(
                "[e2e] docker scenarios",
                "Skipped via --skip-e2e-docker or --skip-environment-integration",
            )
        if self.options.run_real_device_session:
            self._run_stage_command(
                "[e2e] real device session",
                "Real-device Shepherd session scenarios passed",
                [str(REPO_ROOT / "tests/e2e/device/real_device.sh")],
                env=integration_env,
            )
        else:
            self._skip_stage(
                "[e2e] real device session",
                "Skipped via --skip-real or --skip-real-device",
            )
        if self.options.run_apk_e2e:
            self._run_stage_command(
                "[e2e] shepherd apk instrumentation",
                "Shepherd APK instrumentation scenarios passed",
                [str(REPO_ROOT / "tests/e2e/device/e2e_apk.sh")],
                env=integration_env,
            )
        else:
            self._skip_stage(
                "[e2e] shepherd apk instrumentation",
                "Skipped via --skip-real or --skip-apk-e2e",
            )
        if self.options.run_scale:
            scale_env = {
                **integration_env,
                "SCALE_MODE": integration_env.get("SCALE_MODE", "smoke"),
                # Explicit opt-in: default is python_churn; --k6 flips to k6.
                "MSH_USE_K6": "1" if self.options.require_k6 else "0",
            }
            self._run_stage_command(
                "[scale] fake-adapter churn",
                "Scale/load scenarios passed",
                [str(REPO_ROOT / "tests/scale/run_scale.sh")],
                env=scale_env,
            )
        else:
            self._skip_stage(
                "[scale] fake-adapter churn",
                "Skipped via --skip-scale or --skip-environment-integration",
            )
        elapsed = time.monotonic() - self._start_time
        totals, per_stage = self._aggregate_scenario_totals()
        infrastructure_labels = {"Runner configuration", "Checking prerequisites", "Final summary"}
        test_stages = [entry for entry in self.stages if entry.label not in infrastructure_labels]
        stage_counts = {
            "ok": sum(1 for entry in test_stages if entry.status == "ok"),
            "failed": sum(1 for entry in test_stages if entry.status == "failed"),
            "skipped": sum(1 for entry in test_stages if entry.status == "skip"),
        }
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
            self._print_scenario_summary(totals, per_stage, stage_counts)
            if self.options.scan_timings:
                self._print_scan_report(elapsed)
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
            self._print_scenario_summary(totals, per_stage, stage_counts)
            if self.options.scan_timings:
                self._print_scan_report(elapsed)

    def _stage_id_for_label(self, label: str) -> str | None:
        return LABEL_TO_ID.get(label)

    def _stage_enabled_by_id(self, stage_id: str | None) -> tuple[bool, str]:
        """Return (enabled, skip_reason). Skip reason is shown when enabled=False."""
        if stage_id is None:
            return True, ""
        if stage_id in self.options.skip_ids:
            return False, f"Skipped via --skip {stage_id}"
        if self.options.only_ids and stage_id not in self.options.only_ids:
            return False, "Not in --only list"
        return True, ""

    def _extract_marker(self, log_file: Path, prefix: str) -> str | None:
        """Read the last occurrence of a `prefix=value` line from a stage log."""
        if not log_file.is_file():
            return None
        try:
            with log_file.open("r", encoding="utf-8", errors="replace") as handle:
                last_value: str | None = None
                for raw_line in handle:
                    stripped = raw_line.strip()
                    idx = stripped.find(prefix)
                    if idx < 0:
                        continue
                    last_value = stripped[idx + len(prefix):].strip()
                return last_value
        except Exception:
            return None

    def _skip_stage(self, label: str, reason: str) -> None:
        self.current_log_file = None
        self.start_stage(label, "skipped")
        self.update_stage("skip", reason)
        if self.use_plain_logs:
            self.plain.print_step(label)
            self.plain.print_warn(reason)

    def _run_parallel_stages(
        self,
        stages: list[tuple[str, str, Sequence[str], dict[str, str] | None]],
    ) -> None:
        """
        Run the given independent stages concurrently.

        Each stage is a tuple `(label, success_message, command, env)`. They must
        not share state (ports, docker project names, etc) — used for groups
        like `[unit] kotlin` + `[unit] jenkins` + `[build] docker images`.

        The dashboard aggregates them under a single pseudo-"group" header so
        the user still sees live progress; failures are reported per-stage and
        obey --fail-fast semantics (first failure stops the group).
        """
        import concurrent.futures
        # Apply --only/--skip filter before launching anything. Filtered-out
        # stages go through the normal skip path so they show in the dashboard.
        filtered_stages: list[tuple[str, str, Sequence[str], dict[str, str] | None]] = []
        for spec in stages:
            label = spec[0]
            stage_id = self._stage_id_for_label(label)
            enabled, reason = self._stage_enabled_by_id(stage_id)
            if enabled:
                filtered_stages.append(spec)
            else:
                self._skip_stage(label, reason)
        if not filtered_stages:
            return
        stages = filtered_stages
        lock = Lock()
        results: dict[str, int] = {}
        log_files: dict[str, Path] = {}
        start_times: dict[str, float] = {}

        def run_one(spec: tuple[str, str, Sequence[str], dict[str, str] | None]) -> tuple[str, int, Path]:
            label, _success, command, env = spec
            log_slug = "".join(c.lower() if c.isalnum() else "-" for c in label).strip("-")
            log_file = self.log_root / f"{log_slug}.log"
            with lock:
                log_files[label] = log_file
                start_times[label] = time.monotonic()
                self._set_entry_status(self.stages, label, "running", "parallel · starting")
            with log_file.open("w", encoding="utf-8") as log_handle:
                process = subprocess.Popen(
                    list(command),
                    cwd=REPO_ROOT,
                    stdout=log_handle,
                    stderr=subprocess.STDOUT,
                    text=True,
                    env=env,
                )
                exit_code = process.wait()
            return label, exit_code, log_file

        group_label = "parallel (" + ", ".join(spec[0] for spec in stages) + ")"
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(stages)) as executor:
            futures = {executor.submit(run_one, spec): spec for spec in stages}
            pending_labels = {spec[0] for spec in stages}
            reported: set[str] = set()
            if self.use_plain_logs:
                self.plain.print_step(group_label)
            while pending_labels:
                for fut in list(futures.keys()):
                    if not fut.done():
                        continue
                    label = futures[fut][0]
                    if label in reported:
                        continue
                    exit_code, log_file = fut.result()[1:]
                    reported.add(label)
                    results[label] = exit_code
                    success_msg = next(spec[1] for spec in stages if spec[0] == label)
                    if exit_code == 0:
                        with lock:
                            self._set_entry_status(self.stages, label, "ok", success_msg)
                        if self.use_plain_logs:
                            self.plain.print_info(f"  ✓ {label}")
                    else:
                        with lock:
                            self._set_entry_status(self.stages, label, "failed", f"exit code {exit_code}")
                            self.failed = True
                            self.failed_stage_logs.append((label, log_file))
                        if self.use_plain_logs:
                            self.plain.print_error(f"  ✗ {label} (exit {exit_code})")
                    pending_labels.discard(label)
                if self.rich is not None and pending_labels:
                    active_detail = f"parallel · {len(pending_labels)} running: " + ", ".join(sorted(pending_labels))
                    with lock:
                        for label in pending_labels:
                            self._set_entry_status(self.stages, label, "running", active_detail)
                    self.refresh()
                if pending_labels:
                    time.sleep(DYNAMIC_REFRESH_SECONDS)
        # Fail-fast semantics: if any stage in the group failed and user requested
        # fail-fast, bail out immediately; otherwise the caller continues.
        if any(code != 0 for code in results.values()) and not self.options.gradle_continue_enabled:
            self.print_failure_report()
            raise RuntimeError(f"{group_label} failed")

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
        default_phase: str | None = None,
    ) -> None:
        stage_id = self._stage_id_for_label(label)
        enabled, reason = self._stage_enabled_by_id(stage_id)
        if not enabled:
            self._skip_stage(label, reason)
            return
        log_slug = "".join(char.lower() if char.isalnum() else "-" for char in label).strip("-")
        log_file = self.log_root / f"{log_slug}.log"
        self.current_log_file = log_file
        self.current_command_text = join_shell_command(command)
        self.start_stage(label, default_phase or "starting")
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
        exit_code = self._run_dynamic_command(label, command, log_file, env, expected_tasks, default_phase)
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
        default_phase: str | None = None,
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
                phase, test_progress = read_stage_runtime_markers(log_file)
                task_count = count_stage_progress(log_file)
                if test_progress is not None:
                    current, total, _name = test_progress
                    # Test name comes along in the marker for output-log
                    # readability (harnesses format a pretty log line like
                    # `▸ 30/38 · TasksTest.createTask`), but we deliberately
                    # omit it from the dashboard row to keep the stage list
                    # compact. Users who want per-test context read the log.
                    detail = (
                        f"testing [{current}/{total}] · {elapsed}s "
                        f"{SPINNER_FRAMES[spinner_index]}"
                    )
                elif phase is not None:
                    detail = f"{phase} · {elapsed}s {SPINNER_FRAMES[spinner_index]}"
                elif expected_tasks is not None and expected_tasks > 0:
                    phase_name = default_phase or "running"
                    current_step = min(task_count + 1, expected_tasks)
                    detail = f"{phase_name} [{current_step}/{expected_tasks}] · {elapsed}s {SPINNER_FRAMES[spinner_index]}"
                elif default_phase is not None:
                    detail = f"{default_phase} · {elapsed}s {SPINNER_FRAMES[spinner_index]}"
                else:
                    detail = f"{task_count} tasks · {elapsed}s {SPINNER_FRAMES[spinner_index]}"
                self.update_running_stage(detail)
                if exit_code is not None:
                    return exit_code
                spinner_index = (spinner_index + 1) % len(SPINNER_FRAMES)
                time.sleep(DYNAMIC_REFRESH_SECONDS)
