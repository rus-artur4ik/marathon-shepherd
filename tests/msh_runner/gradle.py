"""Building Gradle command lines for the Gradle-backed stages."""

from __future__ import annotations

import shlex
import sys
from typing import Sequence


def is_plain_mode(gradle_args: Sequence[str]) -> bool:
    if not sys.stdout.isatty():
        return True
    return any(
        arg == "--console=plain" or arg == "-Dorg.gradle.console=plain"
        for arg in gradle_args
    )


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


def join_shell_command(command: Sequence[str]) -> str:
    return shlex.join(command)
