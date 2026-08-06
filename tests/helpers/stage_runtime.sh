#!/usr/bin/env bash

STAGE_TOTAL_TEST_SCENARIOS=0
STAGE_CURRENT_TEST_SCENARIO=0

stage_runtime_init() {
    local context="$1"
    local total_test_scenarios="${2:-0}"
    local auto_enable_logs="${3:-true}"
    STAGE_TOTAL_TEST_SCENARIOS="${total_test_scenarios}"
    STAGE_CURRENT_TEST_SCENARIO=0
    set_log_context "${context}"
    if [[ "${auto_enable_logs}" == "true" ]]; then
        enable_dynamic_logs
    fi
}

stage_emit_phase() {
    local phase_name="$1"
    local current="${2:-}"
    local total="${3:-}"
    emit_phase_marker "${phase_name}" "${current}" "${total}"
}

stage_begin_phase() {
    local phase_name="$1"
    local title="$2"
    local current="${3:-}"
    local total="${4:-}"
    stage_emit_phase "${phase_name}" "${current}" "${total}"
    log_step "${title}"
}

stage_begin_test() {
    local title="$1"
    STAGE_CURRENT_TEST_SCENARIO=$((STAGE_CURRENT_TEST_SCENARIO + 1))
    stage_emit_phase "testing"
    emit_test_progress_marker "${STAGE_CURRENT_TEST_SCENARIO}" "${STAGE_TOTAL_TEST_SCENARIOS}"
    log_step "${title}"
}
