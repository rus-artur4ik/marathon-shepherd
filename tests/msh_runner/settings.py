"""Repository location and display tunables shared across the runner."""

from __future__ import annotations

from pathlib import Path


# tests/msh_runner/settings.py -> tests/msh_runner -> tests -> <repo root>
REPO_ROOT: Path = Path(__file__).resolve().parents[2]
LOG_TAIL_LINES: int = 15
FAILURE_LOG_TAIL_LINES: int = 60
DYNAMIC_REFRESH_SECONDS: float = 0.2
SPINNER_FRAMES: tuple[str, ...] = ("|", "/", "-", "\\")
