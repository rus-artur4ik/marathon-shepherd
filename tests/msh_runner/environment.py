"""Probes of the host environment used by the prerequisite checks."""

from __future__ import annotations

import importlib.util
import os
import shutil
import subprocess
from pathlib import Path
from urllib.error import URLError
from urllib.request import urlopen

from .settings import REPO_ROOT


def rich_available() -> bool:
    return importlib.util.find_spec("rich") is not None


def check_command_available(command_name: str) -> tuple[bool, str]:
    if shutil.which(command_name):
        return True, ""
    return False, "command not found"


def check_executable(path: Path) -> tuple[bool, str]:
    if path.is_file() and os.access(path, os.X_OK):
        return True, ""
    return False, f"not executable: {path}"


def check_file(path: Path) -> tuple[bool, str]:
    if path.is_file():
        return True, ""
    return False, f"file not found: {path}"


def check_docker_engine() -> tuple[bool, str]:
    try:
        result = subprocess.run(
            ["docker", "info"],
            cwd=REPO_ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=10,
        )
    except subprocess.TimeoutExpired:
        return False, "Docker Engine check timed out after 10s"
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
