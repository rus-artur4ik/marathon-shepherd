"""Process entry point."""

from __future__ import annotations

from typing import Sequence

from .options import parse_args
from .runner import TestRunner


def main(argv: Sequence[str]) -> int:
    options = parse_args(argv)
    runner = TestRunner(options)
    return runner.run()
