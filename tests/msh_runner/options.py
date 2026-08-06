"""Command-line interface: the option model, its parser and the usage text."""

from __future__ import annotations

import re
import sys
from collections import deque
from dataclasses import dataclass, field
from typing import Sequence

from .stages import STAGE_IDS


@dataclass
class RunnerOptions:
    run_component_cli: bool = True     # [component:cli]  — local services via CLI, Cloud Orchestrator via Docker
    run_component_docker: bool = True  # [component:docker] — per-service Docker isolation tests
    run_docker_integration: bool = True
    run_e2e_docker: bool = True
    run_real_device_session: bool = True
    run_apk_e2e: bool = True
    run_scale: bool = True             # [scale] — fake-adapter smoke load; Docker required
    require_k6: bool = False           # --k6 — use k6 as the churn driver (default: python_churn)
    refresh_cache: bool = False        # --refresh-cache — drop non-source caches (orchestrator config)
    gradle_continue_enabled: bool = True
    scan_timings: bool = False         # --scan — print detailed per-stage timing breakdown at the end
    gradle_test_args: list[str] = field(default_factory=list)
    # Fine-grained id-level filters (group:id, e.g. "unit:kotlin").
    # When `only_ids` is non-empty, only those stage ids run. `skip_ids` removes
    # specific stages even from `--only` lists (explicit veto).
    only_ids: set[str] = field(default_factory=set)
    skip_ids: set[str] = field(default_factory=set)


def usage() -> str:
    return """Usage: tests/run_tests.sh [options] [-- <gradle test args>]

Test groups and subjects:
  [build]            installDist per service (compile artifacts, required by later stages)
  [unit]             Isolated JVM tests per service + Jenkins shared-library unit tests
  [component:cli]    All services run locally via CLI, tested over HTTP — Cloud Orchestrator runs in Docker
  [component:docker] Each service built and run in Docker isolation, tested over HTTP
  [integration]      Full Docker Compose stack, synthetic devices
  [e2e]              Scenario-based Docker suite (manager + all adapters + harnesses)
  [scale]            Fake-adapter smoke load; Docker required. Uses built-in python
                     churn by default; pass --k6 to drive load with k6 instead.

Stage ids (use with --only / --skip / stdin list):
  build:all                              unit:kotlin
  build:docker-images                    unit:jenkins
  component-cli:local-services           scale:fake-adapter-churn
  component-docker:{shepherd-adb,shepherd-farm,shepherd-cuttlefish,manager}
  integration:docker-compose
  e2e:docker-scenarios
  e2e:real-device-session
  e2e:apk-instrumentation

All stages run by default. Use flags to skip stages you don't need:

  --skip-component-cli    Skip [component:cli] local-services stage
  --skip-component-docker Skip [component:docker] per-service Docker stages
  --skip-component-tests  Skip both component:cli and component:docker
  --skip-docker-integration   Skip [integration] docker_compose.sh
  --skip-e2e-docker           Skip [e2e] docker scenarios
  --skip-real                 Skip all hardware-dependent real-device stages
  --skip-real-device          Skip [e2e] real device session
  --skip-apk-e2e              Skip [e2e] shepherd apk instrumentation
  --skip-scale                Skip [scale] fake-adapter churn
  --skip-environment-integration  Skip all Docker-dependent stages
                                  (component:docker + integration + e2e + scale)
  --only <ids>                Run only stage ids from <ids> (comma-separated
                              list of `group:id`, e.g. unit:kotlin,build:all)
  --skip <ids>                Skip specific stage ids (veto, always respected)
  --list-ids                  List stage ids and exit
  --from-stdin                Read one stage id per line from stdin, use as --only list
  --scan                      At the end, print a detailed per-stage timing report
                              (events extracted from stage logs, bottleneck ranking)
  --k6                        Use k6 (grafana.com/k6) as the [scale] load driver
                              instead of the built-in python churn. Adds `k6` as
                              a prerequisite — run fails early with an install
                              hint if k6 isn't on PATH.
  --refresh-cache             Force-refresh cached prebuild artifacts (currently
                              just the Cloud Orchestrator config, which otherwise
                              has a 24h TTL). Use after bumping external configs
                              that the runner doesn't track automatically.
  --fail-fast, --no-continue  Stop on first failed stage (default: continue)
  -h, --help                  Show this help

Examples:
  tests/run_tests.sh
  tests/run_tests.sh --skip-component-docker --skip-e2e-docker
  tests/run_tests.sh --only unit:kotlin,build:all
  printf 'unit:kotlin\\nunit:jenkins\\n' | tests/run_tests.sh --from-stdin
  tests/run_tests.sh --skip scale:fake-adapter-churn,e2e:real-device-session
  tests/run_tests.sh --skip-environment-integration -- --console=plain
  tests/run_tests.sh --fail-fast
  tests/run_tests.sh --scan                  # print timing breakdown at the end
  tests/run_tests.sh --k6                    # drive [scale] load with k6 (must be installed)
  tests/run_tests.sh --refresh-cache         # force-redownload orchestrator config
"""


def _parse_id_list(raw: str) -> set[str]:
    """Parse a comma-separated (or whitespace-separated) list of stage ids."""
    tokens = {token.strip() for token in re.split(r"[,\s]+", raw) if token.strip()}
    unknown = tokens - STAGE_IDS.keys()
    if unknown:
        known = ", ".join(sorted(STAGE_IDS.keys()))
        raise SystemExit(
            f"Unknown stage id(s): {', '.join(sorted(unknown))}\nKnown ids: {known}"
        )
    return tokens


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
        if arg == "--skip-real":
            options.run_real_device_session = False
            options.run_apk_e2e = False
            continue
        if arg == "--skip-real-device":
            options.run_real_device_session = False
            continue
        if arg == "--skip-apk-e2e":
            options.run_apk_e2e = False
            continue
        if arg in ("--skip-environment-integration", "--skip-env-integration"):
            options.run_component_docker = False
            options.run_docker_integration = False
            options.run_e2e_docker = False
            options.run_scale = False
            continue
        if arg == "--run-scale":
            options.run_scale = True
            continue
        if arg == "--skip-scale":
            options.run_scale = False
            continue
        if arg == "--only":
            if not args:
                raise SystemExit("--only requires a comma-separated list of stage ids")
            options.only_ids.update(_parse_id_list(args.popleft()))
            continue
        if arg.startswith("--only="):
            options.only_ids.update(_parse_id_list(arg.split("=", 1)[1]))
            continue
        if arg == "--skip":
            if not args:
                raise SystemExit("--skip requires a comma-separated list of stage ids")
            options.skip_ids.update(_parse_id_list(args.popleft()))
            continue
        if arg.startswith("--skip="):
            options.skip_ids.update(_parse_id_list(arg.split("=", 1)[1]))
            continue
        if arg == "--from-stdin":
            stdin_ids = {line.strip() for line in sys.stdin if line.strip() and not line.startswith("#")}
            if not stdin_ids:
                raise SystemExit("--from-stdin received no stage ids on stdin")
            options.only_ids.update(stdin_ids)
            continue
        if arg == "--list-ids":
            for stage_id, label in STAGE_IDS.items():
                print(f"{stage_id:<40s} {label}")
            raise SystemExit(0)
        if arg in ("--fail-fast", "--no-continue"):
            options.gradle_continue_enabled = False
            continue
        if arg == "--scan":
            options.scan_timings = True
            continue
        if arg == "--k6":
            options.require_k6 = True
            continue
        if arg == "--refresh-cache":
            options.refresh_cache = True
            continue
        if arg in ("-h", "--help"):
            print(usage(), end="")
            raise SystemExit(0)
        options.gradle_test_args.append(arg)
    return options
