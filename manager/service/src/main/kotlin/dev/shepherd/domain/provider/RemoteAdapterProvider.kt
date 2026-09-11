package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.ACCESS_AUTH_NETWORK
import dev.shepherd.adapter.api.ACCESS_EXPOSURE_DIRECT_TCP
import dev.shepherd.adapter.api.ACCESS_PROTOCOL_ADB
import dev.shepherd.adapter.api.ACCESS_TRANSPORT_TCP
import dev.shepherd.adapter.api.AcquireRequest
import dev.shepherd.adapter.api.AcquireResponse
import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterConnection
import dev.shepherd.adapter.api.AdapterConnectionAuth
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.PoolStatusResponse
import dev.shepherd.adapter.api.preferredAdbTcpConnection
import dev.shepherd.domain.metrics.ManagerMetrics
import dev.shepherd.domain.model.AdapterTimeoutsConfig
import dev.shepherd.domain.model.AdbServer
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Generic HTTP client for any Adapter (adb, farm or cuttlefish).
 * The Manager doesn't care what type of adapter is on the other end —
 * all of them expose the same REST contract defined in :adapter:contract.
 *
 * Sends Authorization: Bearer <secret> on all protected endpoints.
 * /health is called without auth (public on the adapter side).
 *
 * Every call is bounded by [timeouts] and reported to [metrics]. A failing or hung adapter
 * degrades to an empty answer instead of blocking the caller indefinitely.
 */
class RemoteAdapterProvider(
    override val name: String,
    private val adapterUrl: String,
    private val accessHost: String,
    private val secret: String,
    private val httpClient: HttpClient,
    private val metrics: ManagerMetrics = ManagerMetrics.NONE,
    private val timeouts: () -> AdapterTimeoutsConfig = { AdapterTimeoutsConfig() }
) : DeviceProvider {

    private val logger = LoggerFactory.getLogger(RemoteAdapterProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Immutable snapshot of the last known adapter state.
     * Written as a single reference swap so readers always see a consistent triplet,
     * never a mix of old access + new inventory.
     */
    private data class AdapterCache(
        val access: AdapterAccess,
        val capabilities: AdapterCapabilities,
        val inventory: List<AdapterDeviceProfile>
    )

    @Volatile
    private var cache: AdapterCache = AdapterCache(
        access = buildUnknownAccess(name, accessHost),
        capabilities = AdapterCapabilities(),
        inventory = emptyList()
    )

    override val adbServer: AdbServer
        get() = cache.access.preferredAdbTcpConnection()?.toAdbServer() ?: AdbServer(host = "unknown", port = 5037)

    override val access: AdapterAccess get() = cache.access
    override val capabilities: AdapterCapabilities get() = cache.capabilities
    override val inventory: List<AdapterDeviceProfile> get() = cache.inventory

    override suspend fun queryDevices(): DevicePoolStatus =
        call(OPERATION_STATUS, timeouts().statusSeconds, fallback = EMPTY_POOL) { outcome ->
            val response = httpClient.get("$adapterUrl/status") {
                if (secret.isNotBlank()) bearerAuth(secret)
            }
            if (response.status.isSuccess()) {
                val body: PoolStatusResponse = json.decodeFromString(response.bodyAsText())
                cache = AdapterCache(normalizeAccessHost(body.access, accessHost), body.capabilities, body.inventory)
                DevicePoolStatus(
                    available = body.pool.available,
                    busy = body.pool.busy,
                    total = body.pool.total
                )
            } else {
                outcome.value = OUTCOME_HTTP_ERROR
                logger.warn("Adapter '{}' status failed: {}", name, response.status)
                EMPTY_POOL
            }
        }

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult = call(
        OPERATION_ACQUIRE,
        timeouts().acquireSeconds,
        fallback = EMPTY_ACQUIRE,
        context = "count=$count api=$apiLevel ttl=${ttlSeconds}s"
    ) { outcome ->
        val request = AcquireRequest(count = count, apiLevel = apiLevel, ttlSeconds = ttlSeconds)
        val response = httpClient.post("$adapterUrl/acquire") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AcquireRequest.serializer(), request))
            if (secret.isNotBlank()) bearerAuth(secret)
        }
        val responseBody: String = response.bodyAsText()

        if (response.status.isSuccess()) {
            val body: AcquireResponse = json.decodeFromString(responseBody)
            val normalizedAccess: AdapterAccess = normalizeAccessHost(body.access, accessHost)
            cache = AdapterCache(normalizedAccess, body.capabilities, body.inventory)
            val adbServers: List<AdbServer> = normalizedAccess.connections
                .filter { connection ->
                    connection.protocol == ACCESS_PROTOCOL_ADB &&
                        connection.transport == ACCESS_TRANSPORT_TCP
                }
                .map { connection -> connection.toAdbServer() }
            val preferredConnection: AdapterConnection? = normalizedAccess.preferredAdbTcpConnection()
            logger.info(
                "Adapter '$name': acquired ${body.acquiredCount}, " +
                    "preferredAccess=${preferredConnection?.host}:${preferredConnection?.port}, " +
                    "adbServers=${adbServers.joinToString { server -> "${server.host}:${server.port}" }}"
            )
            AcquireResult(
                leaseId = body.leaseId,
                acquiredCount = body.acquiredCount,
                adbServers = adbServers
            )
        } else {
            // 503 is the contract's "nothing free right now" — routine while sessions
            // race for the same devices, not an adapter fault.
            val unavailable = response.status == HttpStatusCode.ServiceUnavailable
            outcome.value = if (unavailable) OUTCOME_UNAVAILABLE else OUTCOME_HTTP_ERROR
            val message = "Adapter '{}' acquire failed for count={}, api={}, ttl={}s: {} body={}"
            val arguments = arrayOf(name, count, apiLevel, ttlSeconds, response.status, summarizeBody(responseBody))
            if (unavailable) logger.warn(message, *arguments) else logger.error(message, *arguments)
            EMPTY_ACQUIRE
        }
    }

    override fun supportsDeviceType(deviceType: String): Boolean {
        val supported = cache.capabilities.supportedDeviceTypes
        return supported.isEmpty() || deviceType in supported
    }

    override fun canAllocateApiLevel(apiLevel: String): Boolean {
        val snapshot: AdapterCache = cache
        val supportedApiLevels: List<String> = snapshot.capabilities.supportedApiLevels
        if (supportedApiLevels.isEmpty()) {
            return true
        }
        if (snapshot.capabilities.supportsSelectiveApiAllocation) {
            return apiLevel in supportedApiLevels
        }
        val presentApiLevels: Set<String> = snapshot.inventory.mapNotNull { profile -> profile.apiLevel }.toSet()
        if (presentApiLevels.isEmpty()) {
            return false
        }
        return presentApiLevels.size == 1 && presentApiLevels.single() == apiLevel
    }

    override suspend fun release(leaseId: String) {
        if (leaseId.isBlank()) return
        call(OPERATION_RELEASE, timeouts().releaseSeconds, fallback = Unit, context = "lease=$leaseId") { outcome ->
            val response = httpClient.delete("$adapterUrl/release/$leaseId") {
                if (secret.isNotBlank()) bearerAuth(secret)
            }
            if (!response.status.isSuccess()) {
                outcome.value = OUTCOME_HTTP_ERROR
                logger.error("Adapter '{}' release failed for {}: {}", name, leaseId, response.status)
            }
        }
    }

    // /health is public — no auth header. Failures are expected while an adapter is down
    // and the fleet monitor logs state changes, so they are not logged per call here.
    override suspend fun isHealthy(): Boolean =
        call(OPERATION_HEALTH, timeouts().healthSeconds, fallback = false, logFailures = false) { outcome ->
            httpClient.get("$adapterUrl/health").status.isSuccess().also { healthy ->
                if (!healthy) outcome.value = OUTCOME_HTTP_ERROR
            }
        }

    private class CallOutcome {
        var value: String = OUTCOME_SUCCESS
    }

    /** Runs one adapter call under a timeout, records its outcome and duration, and never throws except on cancellation. */
    private suspend fun <T> call(
        operation: String,
        timeoutSeconds: Long,
        fallback: T,
        context: String = "",
        logFailures: Boolean = true,
        block: suspend (CallOutcome) -> T
    ): T {
        val outcome = CallOutcome()
        val startedAt: Long = System.nanoTime()
        return try {
            withTimeout(timeoutSeconds * MILLIS_PER_SECOND) { block(outcome) }
        } catch (timeout: TimeoutCancellationException) {
            outcome.value = OUTCOME_TIMEOUT
            if (logFailures) logger.error("Adapter '{}' {} timed out after {}s {}", name, operation, timeoutSeconds, context)
            fallback
        } catch (cancelled: CancellationException) {
            outcome.value = OUTCOME_CANCELLED
            throw cancelled
        } catch (error: Exception) {
            outcome.value = OUTCOME_ERROR
            if (logFailures) logger.error("Adapter '{}' {} error {}: {}", name, operation, context, error.message)
            fallback
        } finally {
            metrics.adapterCall(name, operation, outcome.value, Duration.ofNanos(System.nanoTime() - startedAt))
        }
    }

    private fun AdapterConnection.toAdbServer(): AdbServer = AdbServer(host = host, port = port)

    private fun buildUnknownAccess(providerName: String, resolvedAccessHost: String): AdapterAccess {
        return AdapterAccess(
            preferredConnectionId = "$providerName-unknown",
            connections = listOf(
                AdapterConnection(
                    id = "$providerName-unknown",
                    protocol = ACCESS_PROTOCOL_ADB,
                    transport = ACCESS_TRANSPORT_TCP,
                    host = resolvedAccessHost,
                    port = 5037,
                    exposure = ACCESS_EXPOSURE_DIRECT_TCP,
                    auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK),
                    metadata = mapOf("scope" to "unknown", "resolvedBy" to "manager")
                )
            )
        )
    }

    private fun summarizeBody(body: String): String {
        val normalizedBody = body.replace(Regex("\\s+"), " ").trim()
        return if (normalizedBody.length <= REMOTE_PROVIDER_BODY_LIMIT) {
            normalizedBody
        } else {
            normalizedBody.take(REMOTE_PROVIDER_BODY_LIMIT) + "...(truncated)"
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND: Long = 1_000L
        const val OPERATION_HEALTH = "health"
        const val OPERATION_STATUS = "status"
        const val OPERATION_ACQUIRE = "acquire"
        const val OPERATION_RELEASE = "release"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_UNAVAILABLE = "unavailable"
        const val OUTCOME_HTTP_ERROR = "http_error"
        const val OUTCOME_ERROR = "error"
        const val OUTCOME_TIMEOUT = "timeout"
        const val OUTCOME_CANCELLED = "cancelled"
        val EMPTY_POOL = DevicePoolStatus(available = 0, busy = 0, total = 0)
        val EMPTY_ACQUIRE = AcquireResult(leaseId = "", acquiredCount = 0)
    }
}

internal fun normalizeAccessHost(access: AdapterAccess, resolvedAccessHost: String): AdapterAccess {
    if (resolvedAccessHost.isBlank()) {
        return access
    }
    return access.copy(
        connections = access.connections.map { connection ->
            if (connection.protocol == ACCESS_PROTOCOL_ADB &&
                connection.transport == ACCESS_TRANSPORT_TCP &&
                connection.exposure == ACCESS_EXPOSURE_DIRECT_TCP
            ) {
                connection.copy(host = resolvedAccessHost)
            } else {
                connection
            }
        },
        metadata = access.metadata + mapOf("resolvedAccessHost" to resolvedAccessHost)
    )
}

private const val REMOTE_PROVIDER_BODY_LIMIT: Int = 400
