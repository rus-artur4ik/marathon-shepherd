"""Stable stage identifiers.

Ids are what --only/--skip accept; labels keep the ``[group] name`` form that log
scraping relies on, so both are part of the runner's external contract.
"""

from __future__ import annotations


# Stable stage identifiers — (stage_id, display_label). Stage ids are stable
# across runs and used by --only/--skip filters. Display labels keep the
# existing `[group] name` form for backwards-compatible log scraping.
STAGE_IDS: dict[str, str] = {
    "build:all":                          "[build] all",
    "build:docker-images":                "[build] docker images",
    "unit:kotlin":                        "[unit] kotlin",
    "unit:jenkins":                       "[unit] jenkins",
    "component-cli:local-services":       "[component:cli] local services",
    "component-docker:shepherd-adb":      "[component:docker] shepherd-adb",
    "component-docker:shepherd-farm":     "[component:docker] shepherd-farm",
    "component-docker:shepherd-cuttlefish": "[component:docker] shepherd-cuttlefish",
    "component-docker:manager":           "[component:docker] manager",
    "integration:docker-compose":         "[integration] docker compose",
    "e2e:docker-scenarios":               "[e2e] docker scenarios",
    "e2e:real-device-session":            "[e2e] real device session",
    "e2e:apk-instrumentation":            "[e2e] shepherd apk instrumentation",
    "scale:fake-adapter-churn":           "[scale] fake-adapter churn",
}
LABEL_TO_ID: dict[str, str] = {label: stage_id for stage_id, label in STAGE_IDS.items()}
