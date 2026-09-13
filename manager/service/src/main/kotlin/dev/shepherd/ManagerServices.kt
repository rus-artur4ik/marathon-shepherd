package dev.shepherd

import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.devices.DeviceCatalog
import dev.shepherd.domain.events.EventBus
import dev.shepherd.domain.model.AuthConfig
import dev.shepherd.domain.provider.ProviderRegistrationService
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.audit.AuditStore
import dev.shepherd.infra.auth.AccessControl
import dev.shepherd.infra.auth.Accounts
import dev.shepherd.infra.auth.SignIn
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
    val eventBus: EventBus,
    val deviceCatalog: DeviceCatalog,
    val registrations: ProviderRegistrationService,
    /** People: local, LDAP and OIDC accounts and their personal tokens. */
    val accounts: Accounts,
    /** Password, LDAP and OIDC sign-in, and browser sessions. */
    val signIn: SignIn,
    val metrics: MicrometerManagerMetrics = MicrometerManagerMetrics()
) {
    fun authConfig(): AuthConfig = providerRegistry.currentConfig().auth

    /**
     * How old a fleet snapshot may be before a read path polls the adapters itself: two poll
     * intervals, so that in steady state the background poller always answers first.
     */
    fun snapshotMaxAge(): Duration = Duration.ofSeconds(providerRegistry.currentConfig().monitoring.providerPollIntervalSeconds * 2)
}
