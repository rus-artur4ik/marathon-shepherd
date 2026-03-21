package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.AcquireRequest
import dev.shepherd.adapter.api.AcquireResponse
import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterConnection
import dev.shepherd.adapter.api.AdapterConnectionAuth
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.ACCESS_AUTH_NETWORK
import dev.shepherd.adapter.api.ACCESS_EXPOSURE_DIRECT_TCP
import dev.shepherd.adapter.api.ACCESS_PROTOCOL_ADB
import dev.shepherd.adapter.api.ACCESS_TRANSPORT_TCP
import dev.shepherd.adapter.api.PoolStatusResponse
import dev.shepherd.adapter.api.preferredAdbTcpConnection
import dev.shepherd.domain.model.AdbServer
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Generic HTTP client for any Adapter (adb or farm).
 * The Manager doesn't care what type of adapter is on the other end —
 * both expose the same REST contract defined in :adapter:api.
 *
 * Sends Authorization: Bearer <secret> on all protected endpoints.
 * /health is called without auth (public on the adapter side).
 */
class RemoteAdapterProvider(
    override val name: String,
    private val adapterUrl: String,
    private val secret: String,
    private val httpClient: HttpClient
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
        access = buildUnknownAccess(name),
        capabilities = AdapterCapabilities(),
        inventory = emptyList()
    )

    override val adbServer: AdbServer
        get() = cache.access.preferredAdbTcpConnection()?.toAdbServer() ?: AdbServer(host = "unknown", port = 5037)

    override val access: AdapterAccess get() = cache.access
    override val capabilities: AdapterCapabilities get() = cache.capabilities
    override val inventory: List<AdapterDeviceProfile> get() = cache.inventory

    override suspend fun queryDevices(): DevicePoolStatus {
        return try {
            val response = httpClient.get("$adapterUrl/status") {
                if (secret.isNotBlank()) bearerAuth(secret)
            }
            if (response.status.isSuccess()) {
                val body: PoolStatusResponse = json.decodeFromString(response.bodyAsText())
                cache = AdapterCache(body.access, body.capabilities, body.inventory)
                DevicePoolStatus(
                    available = body.pool.available,
                    busy = body.pool.busy,
                    total = body.pool.total
                )
            } else {
                logger.warn("Adapter '$name' status failed: ${response.status}")
                DevicePoolStatus(available = 0, busy = 0, total = 0)
            }
        } catch (e: Exception) {
            logger.error("Adapter '$name' status error: ${e.message}")
            DevicePoolStatus(available = 0, busy = 0, total = 0)
        }
    }

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult {
        return try {
            val request = AcquireRequest(count = count, apiLevel = apiLevel, ttlSeconds = ttlSeconds)
            val response = httpClient.post("$adapterUrl/acquire") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(AcquireRequest.serializer(), request))
                if (secret.isNotBlank()) bearerAuth(secret)
            }

            if (response.status.isSuccess()) {
                val body: AcquireResponse = json.decodeFromString(response.bodyAsText())
                cache = AdapterCache(body.access, body.capabilities, body.inventory)
                val preferredConnection: AdapterConnection? = body.access.preferredAdbTcpConnection()
                logger.info(
                    "Adapter '$name': acquired ${body.acquiredCount}, " +
                        "preferredAccess=${preferredConnection?.host}:${preferredConnection?.port}"
                )
                AcquireResult(leaseId = body.leaseId, acquiredCount = body.acquiredCount)
            } else {
                logger.error("Adapter '$name' acquire failed: ${response.status}")
                AcquireResult(leaseId = "", acquiredCount = 0)
            }
        } catch (e: Exception) {
            logger.error("Adapter '$name' acquire error: ${e.message}")
            AcquireResult(leaseId = "", acquiredCount = 0)
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
        try {
            val response = httpClient.delete("$adapterUrl/release/$leaseId") {
                if (secret.isNotBlank()) bearerAuth(secret)
            }
            if (!response.status.isSuccess()) {
                logger.error("Adapter '$name' release failed for $leaseId: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Adapter '$name' release error for $leaseId: ${e.message}")
        }
    }

    override suspend fun isHealthy(): Boolean {
        return try {
            // /health is public — no auth header needed
            httpClient.get("$adapterUrl/health").status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    private fun AdapterConnection.toAdbServer(): AdbServer = AdbServer(host = host, port = port)

    private fun buildUnknownAccess(providerName: String): AdapterAccess {
        return AdapterAccess(
            preferredConnectionId = "$providerName-unknown",
            connections = listOf(
                AdapterConnection(
                    id = "$providerName-unknown",
                    protocol = ACCESS_PROTOCOL_ADB,
                    transport = ACCESS_TRANSPORT_TCP,
                    host = "unknown",
                    port = 5037,
                    exposure = ACCESS_EXPOSURE_DIRECT_TCP,
                    auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK),
                    metadata = mapOf("scope" to "unknown")
                )
            )
        )
    }
}
