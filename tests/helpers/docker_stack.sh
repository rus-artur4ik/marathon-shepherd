#!/usr/bin/env bash
# Small, focused helpers for bringing docker-compose stacks up and down
# across test stages. Extracted from the copy-pasted logic that used to live
# in smoke_docker.sh / docker_compose.sh / e2e_docker.sh, with first-class
# support for **handoff** between stages:
#
#  * `docker_stack_handoff_requested` — caller asked us to leave the stack
#    alive so the next stage can reuse it (MSH_LEAVE_STACK_RUNNING=1).
#  * `docker_stack_already_running`  — previous stage handed us a live stack
#    (MSH_REUSE_STACK=1); we skip `compose up` entirely.
#
# The handoff saves ~15-20s on every `integration → e2e` transition (no
# teardown, no `up`, no health-wait), while keeping every stage self-contained
# on its own (both env vars default to 0).
#
# Conventions:
#   - All functions emit `MSH_PHASE:` markers so the runner dashboard follows
#     what's happening.
#   - Exit status: 0 on success, non-zero when the stack is in a broken state
#     (caller should fall back to a fresh `up`).
#
# This file is meant to be sourced, not executed.

# shellcheck disable=SC2034  # Variables exported for sourcing scripts.

# ── Handoff predicates ────────────────────────────────────────────────────────

# True when the CURRENT stage should leave its compose stack running for the
# next stage instead of tearing it down. Typically set by the runner when the
# next stage (e.g. e2e) uses the same compose file and can reuse containers.
docker_stack_handoff_requested() {
    [[ "${MSH_LEAVE_STACK_RUNNING:-0}" == "1" ]]
}

# True when the PREVIOUS stage handed off a live stack to us; we should skip
# our own `compose up` and just verify the manager is healthy.
docker_stack_already_running() {
    [[ "${MSH_REUSE_STACK:-0}" == "1" ]]
}

# ── Readiness probes ──────────────────────────────────────────────────────────

# Check that the named containers are all Running (not Healthy — just Running).
# Usage: docker_stack_containers_running <container1> <container2> ...
docker_stack_containers_running() {
    local container
    for container in "$@"; do
        local state
        state="$(docker inspect --format='{{.State.Status}}' "${container}" 2>/dev/null || echo "missing")"
        if [[ "${state}" != "running" ]]; then
            return 1
        fi
    done
    return 0
}

# Verify a handed-off stack is actually usable. We don't just trust the env
# var — containers might have crashed between stages. Returns 0 when the
# caller can safely skip `up`; non-zero otherwise.
# Usage: docker_stack_verify_alive <manager-health-url> <container> [<container> ...]
docker_stack_verify_alive() {
    local manager_url="$1"
    shift
    docker_stack_containers_running "$@" || return 1
    curl -fsS "${manager_url}" >/dev/null 2>&1 || return 1
    return 0
}

# ── Lifecycle ────────────────────────────────────────────────────────────────

# Tear down a compose stack. Silent on success, idempotent. Intentionally does
# NOT remove images — that's the prebuild cache's territory. See
# tests/helpers/docker_prebuild.sh.
docker_stack_teardown() {
    local compose_file="$1"
    local project_name="$2"
    docker compose -f "${compose_file}" -p "${project_name}" down -v --remove-orphans >/dev/null 2>&1 || true
}

# Wait for a set of containers to reach the "healthy" state, polling at 500ms.
# Accepts total deadline in seconds; prints healthy container names as they
# turn healthy so the dashboard can follow progress.
# Usage: docker_stack_wait_healthy <deadline_seconds> <container1> [<container2> ...]
docker_stack_wait_healthy() {
    local deadline_seconds="$1"
    shift
    local containers=("$@")
    local total="${#containers[@]}"
    local deadline
    deadline=$(( $(date +%s) + deadline_seconds ))
    while true; do
        local ready=0
        local idx=0
        for svc in "${containers[@]}"; do
            idx=$((idx + 1))
            local status
            status="$(docker inspect --format='{{.State.Health.Status}}' "${svc}" 2>/dev/null || echo "missing")"
            if [[ "${status}" == "healthy" ]]; then
                ready=$((ready + 1))
            fi
            stage_emit_phase "starting" "${ready}" "${total}" 2>/dev/null || true
        done
        if (( ready == total )); then
            return 0
        fi
        if (( $(date +%s) >= deadline )); then
            return 1
        fi
        sleep 0.5
    done
}
