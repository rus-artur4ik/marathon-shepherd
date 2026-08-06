#!/usr/bin/env python3
"""Marathon Shepherd test runner — entry point.

The implementation lives in the ``msh_runner`` package next to this file. This
script stays at ``tests/run_tests.py`` because ``tests/run_tests.sh``, CI and the
docs invoke it by path.
"""
from __future__ import annotations

import sys

from msh_runner.cli import main

if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
