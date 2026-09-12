package dev.shepherd.domain.model

import dev.shepherd.domain.auth.ClientQuota
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShepherdConfig(
    val providers: List<ProviderConfig>,
    /**
     * Controls what happens when a new session is requested but no devices are available.
     * Defaults to [NoDeviceMode.FAIL_IMMEDIATELY] (original behaviour).
     */
    val noDeviceStrategy: NoDeviceStrategyConfig = NoDeviceStrategyConfig(),
    /** Background polling of adapters that feeds `/health`, `/api/v1/devices` and metrics. */
    val monitoring: MonitoringConfig = MonitoringConfig(),
    /** Upper bounds for manager-to-adapter HTTP calls. */
    val adapterTimeouts: AdapterTimeoutsConfig = AdapterTimeoutsConfig(),
    /** Limits for API clients. */
    val quotas: QuotasConfig = QuotasConfig(),
    /** Audit log retention. */
    val audit: AuditConfig = AuditConfig(),
    /** How long finished sessions are kept. */
    val sessions: SessionsConfig = SessionsConfig(),
    /** How queued sessions are ordered. */
    val scheduler: SchedulerConfig = SchedulerConfig(),
    /** Adapters that register themselves instead of being listed under [providers]. */
    val registration: RegistrationConfig = RegistrationConfig(),
    /** Periodic cleanup of adapter leases that no session owns. */
    val reconciliation: ReconciliationConfig = ReconciliationConfig(),
    /** The MCP endpoint at `/mcp` and the limits for sessions agents open through it. */
    val mcp: McpConfig = McpConfig()
)

/**
 * Sessions opened through MCP are small, short and released soon after the agent stops
 * checking in, on top of the API key's quota. The same limits apply to the `shepherd-mcp`
 * binary through its `MSH_MCP_*` variables.
 */
@Serializable
data class McpConfig(
    val enabled: Boolean = true,
    /** Most devices one acquire_devices call may ask for. */
    val maxDevicesPerSession: Int = 2,
    /** Lifetime of a session when the agent does not ask for one. */
    val defaultTtlSeconds: Long = 1_800,
    /** Longest lifetime an agent may ask for, when acquiring or extending. */
    val maxTtlSeconds: Long = 14_400,
    /** A session is released after this long without get_session or wait_for_session. */
    val idleTimeoutSeconds: Long = 900,
    /** Longest one tool call waits for busy devices. */
    val maxWaitSeconds: Long = 60
) {
    init {
        require(maxDevicesPerSession > 0) { "mcp.maxDevicesPerSession must be positive" }
        require(defaultTtlSeconds > 0) { "mcp.defaultTtlSeconds must be positive" }
        require(maxTtlSeconds >= defaultTtlSeconds) { "mcp.maxTtlSeconds must be at least mcp.defaultTtlSeconds" }
        require(
            idleTimeoutSeconds >= MIN_MCP_IDLE_TIMEOUT_SECONDS
        ) { "mcp.idleTimeoutSeconds must be at least $MIN_MCP_IDLE_TIMEOUT_SECONDS" }
        require(maxWaitSeconds >= 0) { "mcp.maxWaitSeconds must not be negative" }
    }

    private companion object {
        const val MIN_MCP_IDLE_TIMEOUT_SECONDS = 30L
    }
}

@Serializable
enum class QueuePolicy {
    /** Strictly first come, first served: nothing overtakes the head of the queue. */
    @SerialName("fifo")
    FIFO,

    /** Higher session priority first; first come, first served within a priority. */
    @SerialName("priority")
    PRIORITY
}

@Serializable
data class SchedulerConfig(
    val policy: QueuePolicy = QueuePolicy.FIFO
)

@Serializable
data class RegistrationConfig(
    /** Self-registered adapters are told to heartbeat this often. */
    val heartbeatIntervalSeconds: Long = 30,
    /** A registration without a heartbeat for this long stops receiving new sessions. */
    val ttlSeconds: Long = 90
) {
    init {
        require(heartbeatIntervalSeconds > 0) { "registration.heartbeatIntervalSeconds must be positive" }
        require(ttlSeconds > heartbeatIntervalSeconds) { "registration.ttlSeconds must exceed heartbeatIntervalSeconds" }
    }
}

@Serializable
data class ReconciliationConfig(
    val enabled: Boolean = true,
    /** How often adapters are asked for their leases. An orphan is released on the second pass that sees it. */
    val intervalSeconds: Long = 300
) {
    init {
        require(intervalSeconds >= MIN_RECONCILIATION_INTERVAL_SECONDS) {
            "reconciliation.intervalSeconds must be at least $MIN_RECONCILIATION_INTERVAL_SECONDS"
        }
    }

    private companion object {
        const val MIN_RECONCILIATION_INTERVAL_SECONDS = 30L
    }
}

@Serializable
data class QuotasConfig(
    /** Limits for every non-admin client that does not set its own. */
    val defaults: QuotaConfig = QuotaConfig()
)

@Serializable
data class QuotaConfig(
    val maxDevices: Int? = null,
    val maxSessionLifetimeSeconds: Long? = null,
    val maxPriority: Int? = null
) {
    fun toQuota(): ClientQuota = ClientQuota(maxDevices, maxSessionLifetimeSeconds, maxPriority)
}

@Serializable
data class SessionsConfig(
    /** Sessions that were released, expired or failed are deleted this long after they ended. */
    val retentionDays: Long = 30
) {
    init {
        require(retentionDays > 0) { "sessions.retentionDays must be positive" }
    }
}

@Serializable
data class AuditConfig(
    /** Audit entries older than this are deleted. */
    val retentionDays: Long = 90
) {
    init {
        require(retentionDays > 0) { "audit.retentionDays must be positive" }
    }
}

@Serializable
data class MonitoringConfig(
    /** How often every adapter's `/health` and `/status` is polled in the background. */
    val providerPollIntervalSeconds: Long = 10,
    /** Upper bound for one adapter's health or status answer during a poll. */
    val providerPollTimeoutSeconds: Long = 5
) {
    init {
        require(providerPollIntervalSeconds > 0) { "monitoring.providerPollIntervalSeconds must be positive" }
        require(providerPollTimeoutSeconds > 0) { "monitoring.providerPollTimeoutSeconds must be positive" }
    }
}

/**
 * Timeouts for each kind of adapter call. Acquire is generous because on-demand adapters
 * (Cuttlefish) boot devices before they answer; a call that never returns would otherwise
 * pin a session in allocation forever.
 */
@Serializable
data class AdapterTimeoutsConfig(
    val healthSeconds: Long = 5,
    val statusSeconds: Long = 10,
    val acquireSeconds: Long = 600,
    val releaseSeconds: Long = 60
) {
    init {
        require(healthSeconds > 0 && statusSeconds > 0 && acquireSeconds > 0 && releaseSeconds > 0) {
            "adapterTimeouts values must be positive"
        }
    }
}

/**
 * Configuration for the no-device strategy.
 *
 * @param mode         How to react when zero devices can be allocated.
 * @param waitTimeoutSeconds  Maximum time to wait in [NoDeviceMode.WAIT_WITH_TIMEOUT] mode.
 *                            Ignored in [NoDeviceMode.FAIL_IMMEDIATELY] mode.
 */
@Serializable
data class NoDeviceStrategyConfig(
    val mode: NoDeviceMode = NoDeviceMode.FAIL_IMMEDIATELY,
    val waitTimeoutSeconds: Long = 60
)

/** Strategy applied when a session request finds zero available devices. */
@Serializable
enum class NoDeviceMode {
    /** Return HTTP 503 immediately (default). */
    FAIL_IMMEDIATELY,

    /** Keep polling providers until at least one device becomes free, or timeout expires. */
    WAIT_WITH_TIMEOUT,
}

/**
 * A provider is any host running a Shepherd adapter (shepherd-adb, shepherd-farm, or shepherd-cuttlefish).
 * The Manager doesn't care which type — all expose the same REST contract.
 *
 * Security note: the config file containing secrets should be readable only by
 * the shepherd process (chmod 600 / appropriate vault/k8s secret binding).
 */
@Serializable
data class ProviderConfig(
    val name: String,
    /** Base URL of the adapter process on the remote host, e.g. http://192.168.1.10:8091 */
    val url: String,
    /**
     * Optional host exposed to CI/test runners for direct TCP access.
     * Defaults to the host part of [url].
     * Set this only when the device access endpoint lives on a different host than the adapter control plane.
     */
    val accessHost: String? = null,
    /**
     * Bearer token that must match ADAPTER_SECRET on the adapter side.
     * Blank means the adapter is running without auth (local dev only).
     */
    val secret: String = ""
) {
    init {
        // ':' separates provider and device in global device ids (`rack-1:R58M123`).
        require(':' !in name) { "Provider name '$name' must not contain ':'" }
    }
}

/**
 * Placeholder substituted for a non-blank [ProviderConfig.secret] whenever a config
 * leaves the process over HTTP. Chosen so it is obviously not a credential and so a
 * client that round-trips `GET` → edit → `PUT` sends it back unchanged.
 */
const val REDACTED_SECRET: String = "<redacted>"

/**
 * A copy safe to serialize to an API client: every non-blank adapter bearer token is
 * replaced with [REDACTED_SECRET]. Blank secrets stay blank so operators can still see
 * which providers are running unauthenticated.
 *
 * Only admins can read the config, but even an admin key must not be enough to
 * impersonate the manager against every adapter, so the secrets never leave the process.
 */
fun ShepherdConfig.redactSecrets(): ShepherdConfig = copy(
    providers = providers.map { provider ->
        if (provider.secret.isBlank()) provider else provider.copy(secret = REDACTED_SECRET)
    }
)

/**
 * Inverse of [redactSecrets] for an incoming `PUT`: any provider whose secret is still
 * the [REDACTED_SECRET] placeholder keeps the secret already held for that provider
 * name in [current], so a read-modify-write cycle cannot silently erase credentials.
 *
 * A provider that is new, or whose secret was genuinely changed by the caller, is left
 * exactly as submitted.
 */
fun ShepherdConfig.restoreRedactedSecrets(current: ShepherdConfig): ShepherdConfig {
    val knownSecrets = current.providers.associate { it.name to it.secret }
    return copy(
        providers = providers.map { provider ->
            if (provider.secret == REDACTED_SECRET) {
                provider.copy(secret = knownSecrets[provider.name].orEmpty())
            } else {
                provider
            }
        }
    )
}
