"""Preflight behaviour of the TestRunner."""

from __future__ import annotations

from .environment import check_command_available, check_docker_engine, check_executable, check_file
from .settings import REPO_ROOT


class PreflightMixin:
    """Resolving the effective configuration and checking the host can run the selected stages.

    Prerequisites are stage-aware: a tool is only required when a selected stage needs it.

    State lives on TestRunner; this mixin only groups behaviour by concern.
    """

    def seed_prerequisites(self) -> None:
        for label in ("java", "curl", "python3"):
            self.seed_prerequisite(label)
        if self._needs_adb():
            self.seed_prerequisite("adb")
        if self._needs_groovy():
            self.seed_prerequisite("groovy")
        needs_docker = self._needs_docker()
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
        if self.options.run_real_device_session:
            self.seed_prerequisite("entrypoint :: e2e (real device session)")
        if self.options.run_apk_e2e:
            self.seed_prerequisite("entrypoint :: e2e (apk instrumentation)")
        if self.options.run_scale:
            self.seed_prerequisite("entrypoint :: scale (load harness)")
            if self.options.require_k6:
                self.seed_prerequisite("k6")
        if (
            self.options.run_component_docker
            or self.options.run_docker_integration
            or self.options.run_e2e_docker
            or self.options.run_scale
        ):
            self.seed_prerequisite("entrypoint :: docker image pre-build")

    def prepare_configuration(self) -> None:
        all_stage_labels = [
            "[build] all",
            "[build] docker images",
            "[unit] kotlin",
            "[unit] jenkins",
            "[component:cli] local services",
            "[component:docker] shepherd-adb",
            "[component:docker] shepherd-farm",
            "[component:docker] shepherd-cuttlefish",
            "[component:docker] manager",
            "[integration] docker compose",
            "[e2e] docker scenarios",
            "[e2e] real device session",
            "[e2e] shepherd apk instrumentation",
            "[scale] fake-adapter churn",
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
        if self.options.run_real_device_session:
            selected_stages.append("e2e-real-device")
        if self.options.run_apk_e2e:
            selected_stages.append("e2e-apk")
        if self.options.run_scale:
            selected_stages.append("scale")
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

    def _needs_docker(self) -> bool:
        """True when a selected stage actually needs a Docker engine.

        The `run_*` flags describe the default tier set, not the effective one: `--only`
        and `--skip` filter by stage id afterwards. Consulting only the flags made
        `--only unit:kotlin` demand Docker.
        """
        return any(
            self._stage_enabled_by_id(stage_id)[0] and enabled
            for stage_id, enabled in (
                ("component-cli:local-services", self.options.run_component_cli),
                ("component-docker:manager", self.options.run_component_docker),
                ("component-docker:shepherd-adb", self.options.run_component_docker),
                ("component-docker:shepherd-farm", self.options.run_component_docker),
                ("component-docker:shepherd-cuttlefish", self.options.run_component_docker),
                ("integration:docker-compose", self.options.run_docker_integration),
                ("e2e:docker-scenarios", self.options.run_e2e_docker),
                ("scale:fake-adapter-churn", self.options.run_scale),
            )
        )

    def _needs_adb(self) -> bool:
        """True when a selected stage actually talks to a device or an adb server."""
        return any(
            self._stage_enabled_by_id(stage_id)[0] and enabled
            for stage_id, enabled in (
                ("e2e:real-device-session", self.options.run_real_device_session),
                ("e2e:apk-instrumentation", self.options.run_apk_e2e),
                ("component-cli:local-services", self.options.run_component_cli),
            )
        )

    def _needs_groovy(self) -> bool:
        """True when the Jenkins shared-library unit stage is selected."""
        return self._stage_enabled_by_id("unit:jenkins")[0]

    def validate_prerequisites(self) -> None:
        ok = True
        self.current_log_file = None
        self.start_stage("Checking prerequisites", "collecting required tools and entrypoints")
        # java/curl/python3 are needed by the runner itself on every path. adb and groovy
        # are only needed by specific tiers, so requiring them unconditionally would make
        # `--only unit:kotlin` unrunnable on a plain CI runner or a fresh contributor
        # machine — see CONTRIBUTING.md.
        required_commands: list[tuple[str, str]] = [
            ("java", "java"),
            ("curl", "curl"),
            ("python3", "python3"),
        ]
        if self._needs_adb():
            required_commands.append(("adb", "adb"))
        if self._needs_groovy():
            required_commands.append(("groovy", "groovy"))
        for label, command_name in required_commands:
            available, detail = check_command_available(command_name)
            self.set_prerequisite(label, "ok" if available else "missing", detail)
            ok = ok and available
        needs_docker = self._needs_docker()
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
        if self.options.run_scale and self.options.require_k6:
            # Explicit opt-in via --k6 makes k6 a hard requirement. Fail early
            # with an install hint — otherwise the [scale] stage would only
            # notice mid-run when it tries to invoke `k6`.
            k6_available, k6_detail = check_command_available("k6")
            if not k6_available:
                k6_detail = (
                    "`k6` not found on PATH. Install one of: "
                    "`brew install k6` (macOS), "
                    "`sudo gpg -k && sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69 && echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main' | sudo tee /etc/apt/sources.list.d/k6.list && sudo apt-get update && sudo apt-get install k6` (Debian/Ubuntu), "
                    "or grab a binary from https://github.com/grafana/k6/releases. "
                    "Alternatively drop the `--k6` flag to use the built-in Python driver."
                )
            self.set_prerequisite("k6", "ok" if k6_available else "missing", k6_detail)
            ok = ok and k6_available
        executable_entrypoints = [
            ("entrypoint :: ./gradlew", REPO_ROOT / "gradlew"),
            ("entrypoint :: component (cli)", REPO_ROOT / "tests/component/cli/console.sh"),
        ]
        if needs_docker := (
            self.options.run_component_docker
            or self.options.run_docker_integration
            or self.options.run_e2e_docker
            or self.options.run_scale
        ):
            executable_entrypoints.append(("entrypoint :: docker image pre-build", REPO_ROOT / "tests/helpers/docker_prebuild.sh"))
        if self.options.run_component_docker:
            executable_entrypoints.append(("entrypoint :: component (docker)", REPO_ROOT / "tests/component/docker/smoke_docker.sh"))
        if self.options.run_docker_integration:
            executable_entrypoints.append(("entrypoint :: integration (docker-compose)", REPO_ROOT / "tests/integration/docker_compose.sh"))
        if self.options.run_e2e_docker:
            executable_entrypoints.append(("entrypoint :: e2e (docker scenarios)", REPO_ROOT / "tests/e2e/docker/e2e_docker.sh"))
        if self.options.run_real_device_session:
            executable_entrypoints.append(("entrypoint :: e2e (real device session)", REPO_ROOT / "tests/e2e/device/real_device.sh"))
        if self.options.run_apk_e2e:
            executable_entrypoints.append(("entrypoint :: e2e (apk instrumentation)", REPO_ROOT / "tests/e2e/device/e2e_apk.sh"))
        if self.options.run_scale:
            executable_entrypoints.append(("entrypoint :: scale (load harness)", REPO_ROOT / "tests/scale/run_scale.sh"))
        for label, path in executable_entrypoints:
            available, detail = check_executable(path)
            self.set_prerequisite(label, "ok" if available else "missing", detail)
            ok = ok and available
        groovy_entrypoints = [
            ("entrypoint :: unit (jenkins)", REPO_ROOT / "tests/unit/jenkins_unit_test.groovy"),
        ]
        for label, path in groovy_entrypoints:
            available, detail = check_file(path)
            self.set_prerequisite(label, "ok" if available else "missing", detail)
            ok = ok and available
        self.update_stage("ok" if ok else "failed", "all selected prerequisites are available" if ok else "missing prerequisites")
        if self.use_plain_logs:
            self.print_plain_prerequisites()
        if not ok:
            self.failed = True
            raise RuntimeError("Install missing prerequisites or skip the corresponding stage")
