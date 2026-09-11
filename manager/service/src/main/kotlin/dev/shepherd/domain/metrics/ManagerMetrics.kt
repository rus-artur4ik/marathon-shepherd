package dev.shepherd.domain.metrics

import dev.shepherd.domain.FleetSnapshot
import dev.shepherd.domain.model.SessionStatus
import java.time.Duration

/**
 * Everything the manager's domain reports about itself.
 *
 * The domain depends on this interface only: tests and embedded use get [NONE], and the
 * Prometheus wiring lives in `infra.metrics`. Label values passed here must come from small
 * closed sets (statuses, provider names, operation names) — never session ids or other
 * unbounded values, which would explode the number of time series.
 */
interface ManagerMetrics {
    /** A session request passed validation and was persisted. */
    fun sessionCreated(deviceType: String?) {}

    /** A session request was refused before it was persisted; [reason] is a stable slug. */
    fun sessionRejected(reason: String) {}

    /** A queued session received devices; [queueWait] runs from creation to allocation. */
    fun sessionAllocated(queueWait: Duration, devices: Int) {}

    /** A session reached a terminal status; [lifetime] runs from creation to that moment. */
    fun sessionFinished(status: SessionStatus, lifetime: Duration) {}

    /** One HTTP call from the manager to an adapter. [outcome] is success, http_error, error or timeout. */
    fun adapterCall(provider: String, operation: String, outcome: String, duration: Duration) {}

    /** The fleet monitor finished a poll. */
    fun fleetObserved(snapshot: FleetSnapshot) {}

    companion object {
        /** Discards everything. */
        val NONE: ManagerMetrics = object : ManagerMetrics {}
    }
}
