#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUNNER_VENV="${REPO_ROOT}/.msh-runner-venv"
PYTHON_BIN="python3"

if [[ -x "${RUNNER_VENV}/bin/python3" ]]; then
    PYTHON_BIN="${RUNNER_VENV}/bin/python3"
elif [[ -x "${RUNNER_VENV}/bin/python" ]]; then
    PYTHON_BIN="${RUNNER_VENV}/bin/python"
fi

cd "${REPO_ROOT}"
exec "${PYTHON_BIN}" "${SCRIPT_DIR}/run_tests.py" "$@"
