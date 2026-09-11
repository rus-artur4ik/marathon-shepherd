package dev.shepherd.domain

import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.domain.metrics.ManagerMetrics
import dev.shepherd.domain.model.ActiveSessionCounts
import dev.shepherd.domain.provider.DevicePoolStatus
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.ProviderCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Point-in-time view of every provider plus the active session counts. */
data class FleetSnapshot(
    val takenAt: Instant,
    val providers: List<ProviderStatus>,
    val sessions: ActiveSessionCounts
) {
    fun ageAt(now: Instant): Duration = Duration.between(takenAt, now)
}

data class ProviderStatus(
    val name: String,
    val access: AdapterAccess,
    val capabilities: AdapterCapabilities,
    val inventory: List<AdapterDeviceProfile>,
    val pool: DevicePoolStatus,
    val isHealthy: Boolean,
    /** Why the provider is unhealthy or its pool unknown; null when the poll succeeded. */
    val error: String? = null
)

/**
 * Polls every provider's health and pool and keeps the latest [FleetSnapshot].
 *
 * Read paths (`/health`, `/api/v1/devices`, metrics) take the snapshot instead of fanning out
 * to every adapter per request, so a slow or dead adapter costs one bounded poll per interval
 * rather than one per caller. Providers are polled concurrently, each under [pollTimeout], and
 * concurrent callers that need a fresher view share a single refresh.
 */
class FleetMonitor(
    private val providerCatalog: ProviderCatalog,
    private val sessionCounts: suspend () -> ActiveSessionCounts = { ActiveSessionCounts.NONE },
    private val metrics: ManagerMetrics = ManagerMetrics.NONE,
    private val pollTimeout: () -> Duration = { DEFAULT_POLL_TIMEOUT },
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(FleetMonitor::class.java)
    private val refreshLock = Mutex()
    private val lastHealth = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var latest: FleetSnapshot? = null

    /** The latest snapshot when it is at most [maxAge] old, otherwise a fresh poll. */
    suspend fun snapshot(maxAge: Duration): FleetSnapshot {
        latest?.takeIf { snapshot -> isFresh(snapshot, maxAge) }?.let { snapshot -> return snapshot }
        return refreshLock.withLock {
            // Another caller may have refreshed while this one waited for the lock.
            latest?.takeIf { snapshot -> isFresh(snapshot, maxAge) } ?: poll()
        }
    }

    /** Polls every provider now, regardless of the age of the latest snapshot. */
    suspend fun refresh(): FleetSnapshot = refreshLock.withLock { poll() }

    /** Live status of every active provider. */
    suspend fun getProviderStatuses(): List<ProviderStatus> = refresh().providers

    /** Keeps the snapshot fresh in the background until [scope] is cancelled. */
    fun start(scope: CoroutineScope, interval: () -> Duration): Job = scope.launch {
        while (isActive) {
            try {
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Fleet poll failed: {}", error.message)
            }
            delay(interval().toMillis().coerceAtLeast(MIN_INTERVAL_MILLIS))
        }
    }

    private fun isFresh(snapshot: FleetSnapshot, maxAge: Duration): Boolean = snapshot.ageAt(clock.instant()) <= maxAge

    private suspend fun poll(): FleetSnapshot {
        val providers: List<DeviceProvider> = providerCatalog.activeProviders()
        val statuses: List<ProviderStatus> = coroutineScope {
            providers.map { provider -> async { pollProvider(provider) } }.awaitAll()
        }
        val counts: ActiveSessionCounts = try {
            sessionCounts()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Could not count active sessions: {}", error.message)
            latest?.sessions ?: ActiveSessionCounts.NONE
        }
        val snapshot = FleetSnapshot(takenAt = clock.instant(), providers = statuses, sessions = counts)
        latest = snapshot
        metrics.fleetObserved(snapshot)
        return snapshot
    }

    private suspend fun pollProvider(provider: DeviceProvider): ProviderStatus {
        val timeoutMillis: Long = pollTimeout().toMillis()
        var error: String? = null
        val healthy: Boolean = try {
            withTimeout(timeoutMillis) { provider.isHealthy() }.also { isHealthy ->
                if (!isHealthy) error = "adapter reported unhealthy or did not answer /health"
            }
        } catch (timeout: TimeoutCancellationException) {
            error = "health check timed out after $timeoutMillis ms"
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = "health check failed: ${failure.message}"
            false
        }
        val pool: DevicePoolStatus = if (healthy) {
            try {
                withTimeout(timeoutMillis) { provider.queryDevices() }
            } catch (timeout: TimeoutCancellationException) {
                error = "status timed out after $timeoutMillis ms"
                EMPTY_POOL
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "status failed: ${failure.message}"
                EMPTY_POOL
            }
        } else {
            EMPTY_POOL
        }
        logHealthTransition(provider.name, healthy, error)
        return ProviderStatus(
            name = provider.name,
            access = provider.access,
            capabilities = provider.capabilities,
            inventory = provider.inventory,
            pool = pool,
            isHealthy = healthy,
            error = error
        )
    }

    /** Logs only when a provider changes state, so a dead adapter does not log every interval. */
    private fun logHealthTransition(name: String, healthy: Boolean, error: String?) {
        val previous: Boolean? = lastHealth.put(name, healthy)
        if (previous == healthy) {
            return
        }
        if (healthy) {
            logger.info("Provider '{}' is healthy", name)
        } else {
            logger.warn("Provider '{}' is unhealthy: {}", name, error)
        }
    }

    companion object {
        val DEFAULT_POLL_TIMEOUT: Duration = Duration.ofSeconds(5)
        private const val MIN_INTERVAL_MILLIS: Long = 1_000L
        private val EMPTY_POOL = DevicePoolStatus(available = 0, busy = 0, total = 0)
    }
}
