package dev.shepherd.domain

import dev.shepherd.adapter.api.AdapterLease
import dev.shepherd.adapter.api.FEATURE_LEASE_LIST
import dev.shepherd.domain.audit.AuditActions
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.events.EventPublisher
import dev.shepherd.domain.events.EventTypes
import dev.shepherd.domain.metrics.ManagerMetrics
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.ProviderCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Returns adapter leases that no queued or ready session owns.
 *
 * Leases leak when a release call times out or an acquire answers after the manager gave
 * up on it; the device then stays busy on the adapter forever. Adapters that declare
 * [FEATURE_LEASE_LIST] are asked for their leases, and a lease is released only after two
 * consecutive passes found it orphaned. That second look protects an acquire whose lease
 * was not yet recorded when the first pass ran.
 */
class LeaseReconciler(
    private val providerCatalog: ProviderCatalog,
    private val knownLeaseIds: suspend (provider: String) -> Set<String>,
    private val metrics: ManagerMetrics = ManagerMetrics.NONE,
    private val audit: AuditTrail = AuditTrail.NONE,
    private val events: EventPublisher = EventPublisher.NONE
) {
    private val logger = LoggerFactory.getLogger(LeaseReconciler::class.java)

    /** Orphans seen by the previous pass, per provider. */
    private val suspects = ConcurrentHashMap<String, Set<String>>()

    /** One pass over every provider; returns how many leases were released. */
    suspend fun reconcile(): Int {
        val providers: List<DeviceProvider> = providerCatalog.activeProviders()
        suspects.keys.retainAll(providers.map { provider -> provider.name }.toSet())
        var released = 0
        for (provider in providers) {
            if (!provider.supportsFeature(FEATURE_LEASE_LIST)) {
                suspects.remove(provider.name)
                continue
            }
            val held: List<AdapterLease> = listLeases(provider) ?: continue
            val known: Set<String> = knownLeaseIds(provider.name)
            val orphans: Set<String> = held.map { lease -> lease.leaseId }.filterNot { leaseId -> leaseId in known }.toSet()
            val confirmed: Set<String> = orphans intersect suspects[provider.name].orEmpty()
            confirmed.forEach { leaseId -> if (release(provider, leaseId)) released += 1 }
            suspects[provider.name] = orphans - confirmed
        }
        return released
    }

    fun start(scope: CoroutineScope, enabled: () -> Boolean, interval: () -> Duration): Job = scope.launch {
        while (isActive) {
            delay(interval().toMillis())
            if (!enabled()) {
                continue
            }
            try {
                reconcile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn("Lease reconciliation failed: {}", error.message)
            }
        }
    }

    private suspend fun listLeases(provider: DeviceProvider): List<AdapterLease>? = try {
        provider.leases()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        logger.warn("Could not list leases of '{}': {}", provider.name, error.message)
        null
    }

    private suspend fun release(provider: DeviceProvider, leaseId: String): Boolean {
        logger.warn("Releasing orphaned lease {} on '{}': no queued or ready session owns it", leaseId, provider.name)
        try {
            withTimeout(RELEASE_TIMEOUT_MS) { provider.release(leaseId) }
        } catch (timeout: TimeoutCancellationException) {
            logger.error("Timed out releasing orphaned lease {} on '{}'", leaseId, provider.name)
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error("Failed to release orphaned lease {} on '{}': {}", leaseId, provider.name, error.message)
            return false
        }
        metrics.leaseReclaimed(provider.name)
        audit.record(Actor.SYSTEM, AuditActions.LEASE_RECLAIM, target = leaseId, details = mapOf("provider" to provider.name))
        events.publish(
            EventTypes.LEASE_RECLAIMED,
            buildJsonObject {
                put("provider", provider.name)
                put("leaseId", leaseId)
            }
        )
        return true
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS: Long = 30_000L
    }
}
