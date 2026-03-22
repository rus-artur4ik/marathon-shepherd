package dev.shepherd.domain

import dev.shepherd.adapter.api.DEVICE_TYPE_EMULATOR
import dev.shepherd.adapter.api.DEVICE_TYPE_PHYSICAL
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.model.SessionStatus.FAILED
import dev.shepherd.domain.provider.ProviderCatalog
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

private const val RELEASE_TIMEOUT_MS = 10_000L
private val SUPPORTED_DEVICE_TYPES: Set<String> = linkedSetOf(DEVICE_TYPE_PHYSICAL, DEVICE_TYPE_EMULATOR)
private const val SUPPORTED_DEVICE_TYPES_TEXT = "physical, emulator"

class SessionManager(
    private val providerCatalog: ProviderCatalog,
    private val stateStore: StateStore
) {
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    suspend fun createSession(
        requestedDevices: Int,
        apiLevel: String,
        ttlSeconds: Long,
        deviceType: String? = null
    ): Session {
        require(requestedDevices > 0) { "Requested devices must be greater than zero" }
        require(ttlSeconds > 0) { "Session TTL must be greater than zero" }
        val normalizedDeviceType: String? = deviceType
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { value -> value.isNotEmpty() }
        require(normalizedDeviceType == null || normalizedDeviceType in SUPPORTED_DEVICE_TYPES) {
            "Unsupported deviceType '$deviceType'. Supported values: $SUPPORTED_DEVICE_TYPES_TEXT"
        }

        val sessionId = "sess_${UUID.randomUUID().toString().take(8)}"
        val now = Instant.now()
        val expiresAt = now.plusSeconds(ttlSeconds)
        val providers = providerCatalog.activeProviders()
            .filter { provider -> normalizedDeviceType == null || provider.supportsDeviceType(normalizedDeviceType) }

        val pendingSession = Session(
            id = sessionId,
            status = SessionStatus.PENDING,
            requestedDevices = requestedDevices,
            allocatedDevices = 0,
            apiLevel = apiLevel,
            adbServers = emptyList(),
            createdAt = now,
            expiresAt = expiresAt,
            releasedAt = null
        )

        logger.info("Creating session $sessionId: $requestedDevices devices, API $apiLevel")
        stateStore.saveSession(pendingSession)

        var remaining = requestedDevices
        val leases = mutableListOf<SessionLease>()
        try {
            for (provider in providers) {
                if (remaining <= 0) break
                val availablePool = try {
                    provider.queryDevices()
                } catch (e: Exception) {
                    logger.warn("Provider '${provider.name}' inventory refresh failed: ${e.message}")
                    continue
                }
                if (availablePool.available <= 0) {
                    logger.info("Provider '${provider.name}' has no available devices for session $sessionId")
                    continue
                }
                if (!provider.canAllocateApiLevel(apiLevel)) {
                    logger.info("Provider '${provider.name}' cannot safely satisfy API $apiLevel for session $sessionId")
                    continue
                }

                val result = provider.acquire(
                    count = minOf(remaining, availablePool.available),
                    apiLevel = apiLevel,
                    ttlSeconds = ttlSeconds
                )
                if (result.acquiredCount > 0) {
                    leases.add(SessionLease(provider.name, result.leaseId, result.acquiredCount))
                    stateStore.saveSessionLease(sessionId, provider.name, result.leaseId, result.acquiredCount)
                    remaining -= result.acquiredCount
                    logger.info("Provider '${provider.name}': acquired ${result.acquiredCount} devices")
                }
            }

            val totalAllocated = leases.sumOf { it.count }
            if (totalAllocated == 0) {
                throw IllegalStateException("No devices available for session $sessionId")
            }

            if (totalAllocated < requestedDevices) {
                logger.warn("Session $sessionId: only $totalAllocated of $requestedDevices devices available")
            }

            val activeProviderNames = leases.map { it.providerName }.toSet()
            val adbServers = providers
                .filter { it.name in activeProviderNames }
                .map { it.adbServer }

            val session = pendingSession.copy(
                status = SessionStatus.READY,
                allocatedDevices = totalAllocated,
                adbServers = adbServers
            )
            stateStore.updateSession(session)
            logger.info("Session $sessionId ready: $totalAllocated devices across ${leases.size} providers")
            return session
        } catch (e: Exception) {
            logger.error("Session creation failed for $sessionId", e)
            rollbackFailedSession(sessionId, leases)
            when (e) {
                is IllegalArgumentException -> throw e
                is IllegalStateException -> throw e
                else -> throw IllegalStateException("Failed to create session $sessionId", e)
            }
        }
    }

    private suspend fun rollbackFailedSession(sessionId: String, leases: List<SessionLease>) {
        for (lease in leases.asReversed()) {
            val provider = providerCatalog.resolveProvider(lease.providerName)
            if (provider == null) {
                logger.warn("Provider '${lease.providerName}' not found during rollback of $sessionId")
                continue
            }
            try {
                withTimeout(RELEASE_TIMEOUT_MS) { provider.release(lease.leaseId) }
                logger.info("Rolled back lease ${lease.leaseId} on '${lease.providerName}'")
            } catch (e: TimeoutCancellationException) {
                logger.error("Timed out rolling back lease ${lease.leaseId} on '${lease.providerName}'")
            } catch (e: Exception) {
                logger.error("Failed to roll back lease ${lease.leaseId} on '${lease.providerName}'", e)
            }
        }
        stateStore.updateSessionStatus(sessionId, FAILED)
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
        val expired = stateStore.listExpiredSessions()
        for (session in expired) {
            logger.info("Cleaning up expired session ${session.id}")
            releaseSessionWithStatus(session.id, SessionStatus.EXPIRED)
        }
    }

    private suspend fun releaseSessionWithStatus(sessionId: String, targetStatus: SessionStatus): Boolean {
        val session = stateStore.getSession(sessionId) ?: return false

        if (session.status == SessionStatus.RELEASED || session.status == SessionStatus.EXPIRED) {
            logger.info("Session $sessionId already ${session.status}")
            return true
        }

        logger.info("Releasing session $sessionId → $targetStatus")

        val leases = stateStore.getSessionLeases(sessionId)
        for (lease in leases) {
            val provider = providerCatalog.resolveProvider(lease.providerName)
            if (provider == null) {
                logger.warn("Provider '${lease.providerName}' not found, skipping release")
                continue
            }
            try {
                withTimeout(RELEASE_TIMEOUT_MS) { provider.release(lease.leaseId) }
                logger.info("Released lease ${lease.leaseId} on '${lease.providerName}'")
            } catch (e: TimeoutCancellationException) {
                logger.error("Timed out releasing lease ${lease.leaseId} on '${lease.providerName}'")
            } catch (e: Exception) {
                logger.error("Failed to release lease ${lease.leaseId} on '${lease.providerName}'", e)
            }
        }

        stateStore.updateSessionStatus(sessionId, targetStatus)
        return true
    }
}

private data class SessionLease(
    val providerName: String,
    val leaseId: String,
    val count: Int
)
