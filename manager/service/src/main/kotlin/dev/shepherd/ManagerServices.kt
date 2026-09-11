package dev.shepherd

import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.audit.AuditStore
import dev.shepherd.infra.auth.AccessControl
import dev.shepherd.infra.metrics.MicrometerManagerMetrics
import dev.shepherd.infra.state.StateStore
import java.time.Duration

/** Everything the HTTP layer needs, assembled once by `main` (or by a test). */
class ManagerServices(
    val providerRegistry: ProviderRegistry,
    val stateStore: StateStore,
    val sessionManager: SessionManager,
    val fleetMonitor: FleetMonitor,
    val accessControl: AccessControl,
    val auditStore: AuditStore,
    val audit: AuditTrail,
    val metrics: MicrometerManagerMetrics = MicrometerManagerMetrics()
) {
    /**
     * How old a fleet snapshot may be before a read path polls the adapters itself: two poll
     * intervals, so that in steady state the background poller always answers first.
     */
    fun snapshotMaxAge(): Duration = Duration.ofSeconds(providerRegistry.currentConfig().monitoring.providerPollIntervalSeconds * 2)
}
