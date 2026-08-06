package dev.shepherd.domain

import dev.shepherd.adapter.api.DEVICE_TYPE_EMULATOR
import dev.shepherd.adapter.api.DEVICE_TYPE_PHYSICAL
import dev.shepherd.domain.allocation.NoMatchingDevicesReport
import dev.shepherd.domain.allocation.ProviderMatcher
import dev.shepherd.domain.model.ApiSelector
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.model.SessionStatus.FAILED
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.ProviderCatalog
import dev.shepherd.infra.config.ConfigStore
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.*

private const val RELEASE_TIMEOUT_MS = 10_000L
private const val WAIT_POLL_INTERVAL_MS = 1_000L
private const val DEFAULT_WAIT_TIMEOUT_SECONDS = 20L
private const val MAX_WAIT_TIMEOUT_SECONDS = 30L
private const val STALE_PENDING_HEARTBEAT_SECONDS = 90L
private val SUPPORTED_DEVICE_TYPES: Set<String> = linkedSetOf(DEVICE_TYPE_PHYSICAL, DEVICE_TYPE_EMULATOR)
private const val SUPPORTED_DEVICE_TYPES_TEXT = "physical, emulator"

class SessionManager(
    private val providerCatalog: ProviderCatalog,
    private val stateStore: StateStore,
    @Suppress("unused")
    private val configStore: ConfigStore? = null,
) {
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    suspend fun createSession(requestedDevices: Int, api: String?, ttlSeconds: Long, deviceType: String? = null): Session {
        require(requestedDevices > 0) { "Requested devices must be greater than zero" }
        require(ttlSeconds > 0) { "Session TTL must be greater than zero" }

        val normalizedDeviceType: String? = normalizeDeviceType(deviceType)
        val apiSelector: ApiSelector = ApiSelector.parse(api)
        cleanupStalePendingSessions()

        val providers: List<DeviceProvider> = refreshMatchingProviders(normalizedDeviceType)
        check(ProviderMatcher.hasRegisteredMatchingDevices(providers, normalizedDeviceType, apiSelector)) {
            NoMatchingDevicesReport.render(providerCatalog.activeProviders(), normalizedDeviceType, apiSelector)
        }

        val now = Instant.now()
        val session = Session(
            id = "sess_${UUID.randomUUID().toString().take(8)}",
            status = SessionStatus.PENDING,
            requestedDevices = requestedDevices,
            allocatedDevices = 0,
            api = apiSelector.rawValue,
            deviceType = normalizedDeviceType,
            adbServers = emptyList(),
            createdAt = now,
            expiresAt = now.plusSeconds(ttlSeconds),
            lastHeartbeatAt = now,
            releasedAt = null
        )

        logger.info(
            "Creating session {}: maxDevices={}, api={}, deviceType={}",
            session.id,
            session.requestedDevices,
            session.api ?: "any",
            session.deviceType ?: "all"
        )
        stateStore.saveSession(session)
        return attemptReadyAllocation(session.id) ?: session
    }

    suspend fun waitForSession(sessionId: String, timeoutSeconds: Long = DEFAULT_WAIT_TIMEOUT_SECONDS): Session {
        require(timeoutSeconds > 0) { "timeoutSeconds must be greater than zero" }
        val clampedTimeoutSeconds: Long = timeoutSeconds.coerceAtMost(MAX_WAIT_TIMEOUT_SECONDS)
        val deadline: Instant = Instant.now().plusSeconds(clampedTimeoutSeconds)

        while (true) {
            cleanupStalePendingSessions()
            val session: Session = stateStore.getSession(sessionId)
                ?: throw IllegalStateException("Session $sessionId not found")
            val heartbeatAt = Instant.now()
            stateStore.touchSessionHeartbeat(sessionId, heartbeatAt)

            if (session.status != SessionStatus.PENDING) {
                return session
            }

            val readySession: Session? = attemptReadyAllocation(sessionId)
            if (readySession != null) {
                return readySession
            }

            val refreshedSession: Session = stateStore.getSession(sessionId)
                ?: throw IllegalStateException("Session $sessionId not found")
            if (refreshedSession.status != SessionStatus.PENDING || Instant.now() >= deadline) {
                return refreshedSession
            }

            delay(WAIT_POLL_INTERVAL_MS)
        }
    }

    suspend fun getSession(sessionId: String): Session? {
        return stateStore.getSession(sessionId)
    }

    suspend fun releaseSession(sessionId: String): Boolean {
        return releaseSessionWithStatus(sessionId, SessionStatus.RELEASED)
    }

    suspend fun listSessions(statusFilter: String? = null): List<Session> {
        val filter = statusFilter?.let { runCatching { SessionStatus.valueOf(it.uppercase()) }.getOrNull() }
        return stateStore.listSessions(filter)
    }

    suspend fun hasActiveSessionsForProvider(providerName: String): Boolean {
        return stateStore.hasActiveLeasesForProvider(providerName)
    }

    suspend fun cleanupExpiredSessions() {
        cleanupStalePendingSessions()
        val expired = stateStore.listExpiredSessions()
        for (session in expired) {
            logger.info("Cleaning up expired session {}", session.id)
            releaseSessionWithStatus(session.id, SessionStatus.EXPIRED)
        }
    }

    suspend fun getQueuePosition(sessionId: String): Int? {
        cleanupStalePendingSessions()
        val session: Session = stateStore.getSession(sessionId) ?: return null
        if (session.status != SessionStatus.PENDING) {
            return null
        }
        val pendingSessions: List<Session> = stateStore.listPendingSessions()
        val sessionIndex: Int = pendingSessions.indexOfFirst { pending -> pending.id == sessionId }
        return if (sessionIndex >= 0) sessionIndex + 1 else null
    }

    private suspend fun attemptReadyAllocation(sessionId: String): Session? {
        val session: Session = stateStore.getSession(sessionId) ?: return null
        if (session.status != SessionStatus.PENDING) {
            return session
        }
        if (!isHeadOfQueue(sessionId)) {
            return null
        }
        return allocatePendingSession(session)
    }

    private suspend fun allocatePendingSession(session: Session): Session? {
        val providers: List<DeviceProvider> = refreshMatchingProviders(session.deviceType)
        val apiSelector: ApiSelector = ApiSelector.parse(session.api)
        if (!ProviderMatcher.hasRegisteredMatchingDevices(providers, session.deviceType, apiSelector)) {
            logger.warn("Session {} no longer has any matching registered devices", session.id)
            return null
        }

        val leases = mutableListOf<SessionLease>()
        try {
            var remainingDevices: Int = session.requestedDevices
            for (provider in providers) {
                if (remainingDevices <= 0) {
                    break
                }
                val poolStatus = provider.queryDevices()
                val onDemandProvider: Boolean = ProviderMatcher.supportsOnDemandAllocation(provider)
                if (poolStatus.available <= 0 && !onDemandProvider) {
                    logger.info(
                        "Skipping provider '{}' for session {}: available=0 busy={} total={} onDemand={}",
                        provider.name,
                        session.id,
                        poolStatus.busy,
                        poolStatus.total,
                        onDemandProvider
                    )
                    continue
                }
                val candidateApiLevels: List<String> = ProviderMatcher.resolveCandidateApiLevels(provider, apiSelector)
                if (candidateApiLevels.isEmpty()) {
                    logger.info(
                        "Skipping provider '{}' for session {}: no candidate api levels for selector={} supported={} inventoryApis={}",
                        provider.name,
                        session.id,
                        session.api ?: "any",
                        provider.capabilities.supportedApiLevels,
                        provider.inventory.mapNotNull { profile -> profile.apiLevel }.distinct()
                    )
                    continue
                }

                var providerRemaining: Int = if (onDemandProvider) {
                    remainingDevices
                } else {
                    minOf(remainingDevices, poolStatus.available)
                }
                for (apiLevel in candidateApiLevels) {
                    if (providerRemaining <= 0 || remainingDevices <= 0) {
                        break
                    }
                    val ttlSeconds: Long = remainingTtlSeconds(session.expiresAt)
                    if (ttlSeconds <= 0) {
                        break
                    }
                    logger.info(
                        "Requesting up to {} device(s) from provider '{}' for session {} at api={} ttl={}s onDemand={} pool={}/{}/{}",
                        providerRemaining,
                        provider.name,
                        session.id,
                        apiLevel,
                        ttlSeconds,
                        onDemandProvider,
                        poolStatus.available,
                        poolStatus.busy,
                        poolStatus.total
                    )
                    val result = provider.acquire(
                        count = providerRemaining,
                        apiLevel = apiLevel,
                        ttlSeconds = ttlSeconds
                    )
                    if (result.acquiredCount <= 0) {
                        logger.warn(
                            "Provider '{}' returned 0 device(s) for session {} at api={} requested={} onDemand={} pool={}/{}/{}",
                            provider.name,
                            session.id,
                            apiLevel,
                            providerRemaining,
                            onDemandProvider,
                            poolStatus.available,
                            poolStatus.busy,
                            poolStatus.total
                        )
                        continue
                    }
                    val leaseAdbServers = resolveLeaseAdbServers(provider, result)
                    leases += SessionLease(
                        providerName = provider.name,
                        leaseId = result.leaseId,
                        count = result.acquiredCount,
                        adbServers = leaseAdbServers
                    )
                    stateStore.saveSessionLease(session.id, provider.name, result.leaseId, result.acquiredCount)
                    remainingDevices -= result.acquiredCount
                    providerRemaining -= result.acquiredCount
                    logger.info(
                        "Provider '{}' acquired {} device(s) for session {} at api={}",
                        provider.name,
                        result.acquiredCount,
                        session.id,
                        apiLevel
                    )
                }
            }

            val totalAllocated: Int = leases.sumOf { lease -> lease.count }
            if (totalAllocated <= 0) {
                return null
            }

            val readySession = session.copy(
                status = SessionStatus.READY,
                allocatedDevices = totalAllocated,
                adbServers = leases.flatMap { lease -> lease.adbServers }.distinct(),
                lastHeartbeatAt = Instant.now()
            )
            stateStore.updateSession(readySession)
            logger.info(
                "Session {} ready with {} of {} requested device(s)",
                readySession.id,
                readySession.allocatedDevices,
                readySession.requestedDevices
            )
            return readySession
        } catch (error: Exception) {
            logger.error("Failed to allocate session {}", session.id, error)
            rollbackFailedSession(session.id, leases)
            throw when (error) {
                is IllegalArgumentException -> error
                is IllegalStateException -> error
                else -> IllegalStateException("Failed to allocate session ${session.id}", error)
            }
        }
    }

    private suspend fun rollbackFailedSession(sessionId: String, leases: List<SessionLease>) {
        for (lease in leases.asReversed()) {
            val provider = providerCatalog.resolveProvider(lease.providerName)
            if (provider == null) {
                logger.warn("Provider '{}' not found during rollback of {}", lease.providerName, sessionId)
                continue
            }
            try {
                withTimeout(RELEASE_TIMEOUT_MS) { provider.release(lease.leaseId) }
                logger.info("Rolled back lease {} on '{}'", lease.leaseId, lease.providerName)
            } catch (error: TimeoutCancellationException) {
                logger.error("Timed out rolling back lease {} on '{}'", lease.leaseId, lease.providerName)
            } catch (error: Exception) {
                logger.error("Failed to roll back lease {} on '{}'", lease.leaseId, lease.providerName, error)
            }
        }
        stateStore.updateSessionStatus(sessionId, FAILED)
    }

    private suspend fun cleanupStalePendingSessions() {
        val staleBefore: Instant = Instant.now().minusSeconds(STALE_PENDING_HEARTBEAT_SECONDS)
        val staleSessions: List<Session> = stateStore.listStalePendingSessions(staleBefore)
        for (session in staleSessions) {
            logger.warn(
                "Cleaning up stale pending session {} (last heartbeat at {})",
                session.id,
                session.lastHeartbeatAt
            )
            releaseSessionWithStatus(session.id, SessionStatus.FAILED)
        }
    }

    private suspend fun refreshMatchingProviders(deviceType: String?): List<DeviceProvider> {
        return providerCatalog.activeProviders()
            .filter { provider -> deviceType == null || provider.supportsDeviceType(deviceType) }
            .onEach { provider ->
                runCatching { provider.queryDevices() }
                    .onFailure { error ->
                        logger.warn("Provider '{}' inventory refresh failed: {}", provider.name, error.message)
                    }
            }
    }

    private suspend fun isHeadOfQueue(sessionId: String): Boolean {
        val pendingSessions: List<Session> = stateStore.listPendingSessions()
        return pendingSessions.firstOrNull()?.id == sessionId
    }

    private fun normalizeDeviceType(rawDeviceType: String?): String? {
        val normalizedDeviceType: String? = rawDeviceType
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { value -> value.isNotEmpty() }
        require(normalizedDeviceType == null || normalizedDeviceType in SUPPORTED_DEVICE_TYPES) {
            "Unsupported deviceType '$rawDeviceType'. Supported values: $SUPPORTED_DEVICE_TYPES_TEXT"
        }
        return normalizedDeviceType
    }

    private fun remainingTtlSeconds(expiresAt: Instant): Long {
        return Duration.between(Instant.now(), expiresAt).seconds.coerceAtLeast(1L)
    }

    private fun resolveLeaseAdbServers(
        provider: DeviceProvider,
        result: dev.shepherd.domain.provider.AcquireResult
    ): List<dev.shepherd.domain.model.AdbServer> {
        if (result.adbServers.isNotEmpty()) {
            return result.adbServers
        }
        val accessIsolation: String? = provider.capabilities.metadata["accessIsolation"]
        require(accessIsolation != "lease-scoped-proxy") {
            "Provider '${provider.name}' acquired devices but did not return lease-scoped adbServers"
        }
        return listOf(provider.adbServer)
    }

    private suspend fun releaseSessionWithStatus(sessionId: String, targetStatus: SessionStatus): Boolean {
        val session = stateStore.getSession(sessionId) ?: return false
        if (session.status == SessionStatus.RELEASED || session.status == SessionStatus.EXPIRED) {
            logger.info("Session {} already {}", sessionId, session.status)
            return true
        }

        logger.info("Releasing session {} -> {}", sessionId, targetStatus)
        val leases = stateStore.getSessionLeases(sessionId)
        for (lease in leases) {
            val provider = providerCatalog.resolveProvider(lease.providerName)
            if (provider == null) {
                logger.warn("Provider '{}' not found, skipping release", lease.providerName)
                continue
            }
            try {
                withTimeout(RELEASE_TIMEOUT_MS) { provider.release(lease.leaseId) }
                logger.info("Released lease {} on '{}'", lease.leaseId, lease.providerName)
            } catch (error: TimeoutCancellationException) {
                logger.error("Timed out releasing lease {} on '{}'", lease.leaseId, lease.providerName)
            } catch (error: Exception) {
                logger.error("Failed to release lease {} on '{}'", lease.leaseId, lease.providerName, error)
            }
        }

        stateStore.updateSessionStatus(sessionId, targetStatus)
        return true
    }
}

private data class SessionLease(
    val providerName: String,
    val leaseId: String,
    val count: Int,
    val adbServers: List<dev.shepherd.domain.model.AdbServer>
)
