package dev.shepherd.adapter.api

import dev.shepherd.common.BuildInfo
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Duration

data class AdapterStatus(
    val pool: AdapterPool,
    val access: AdapterAccess? = null,
    val inventory: List<AdapterDeviceProfile> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    /** One entry per device for adapters that can tell devices apart; pool-only adapters leave it empty. */
    val devices: List<AdapterDevice> = emptyList()
)

/**
 * Partial result returned by [AdapterHandler.acquire]; routing layer adds default access/capabilities when needed.
 * [leaseId] is null when [acquiredCount] is zero — the routing layer returns 503 in that case
 * and never sends the empty ID to the caller, making the failure structurally unambiguous.
 */
data class AcquireResult(
    val leaseId: String?,
    val acquiredCount: Int,
    val access: AdapterAccess? = null,
    val inventory: List<AdapterDeviceProfile> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    /** The leased devices and the access connection reaching each, for adapters that know them at acquire time. */
    val devices: List<AdapterLeasedDevice> = emptyList()
)

/**
 * Single override point for adapter-specific logic.
 * Subclasses implement the four service-specific operations;
 * HTTP wiring is handled once by [Route.adapterRoutes].
 */
abstract class AdapterHandler(val adapterType: String, val version: String = BuildInfo.version) {
    abstract suspend fun isHealthy(): Boolean
    abstract suspend fun status(): AdapterStatus

    /** Return [AcquireResult] with acquiredCount == 0 when no devices are available. */
    abstract suspend fun acquire(request: AcquireRequest): AcquireResult

    /** Return true on success, false on failure. */
    abstract suspend fun release(leaseId: String): Boolean

    /**
     * Keeps [leaseId] for [ttlSeconds] from now.
     *
     * Null means this adapter cannot renew leases and the route answers 501, so adapters that do
     * not declare [FEATURE_LEASE_RENEW] need no override. False means the lease is unknown: the
     * devices behind it are gone, and the manager should stop relying on them.
     */
    open suspend fun renew(leaseId: String, ttlSeconds: Long): Boolean? = null

    /**
     * Every lease this adapter holds, so the manager can find leases it lost track of, e.g. after
     * a restart. Null means this adapter cannot list them and the route answers 501.
     */
    open suspend fun leases(): List<AdapterLease>? = null

    open fun capabilities(env: AdapterEnv): AdapterCapabilities {
        return AdapterCapabilities(
            allocationModes = listOf("lease"),
            supportedProtocols = listOf(ACCESS_PROTOCOL_ADB),
            supportedExposureModes = listOf(env.accessMode),
            supportedDeviceTypes = emptyList(),
            supportsTestAccessAdb = true,
            supportsTestAccessGrpc = false,
            supportsTestAccessConsole = false,
            features = listOf("inventory", "lease", "release"),
            metadata = mapOf("controlPlaneAuth" to if (env.authEnabled) "bearer" else "none")
        )
    }

    open fun defaultAccess(env: AdapterEnv, requestHost: String): AdapterAccess = env.buildDefaultAccess(adapterType, requestHost)
}

/** Registers all adapter endpoints. Call this once from [startAdapterServer]. */
fun Route.adapterRoutes(handler: AdapterHandler, env: AdapterEnv, metrics: AdapterMetrics? = null) {
    get("/health") {
        call.respond(
            HealthResponse(
                status = if (handler.isHealthy()) "healthy" else "unhealthy",
                version = handler.version,
                adapterType = handler.adapterType
            )
        )
    }

    // configureAdapterAuth always registers the scheme; `optional` is what turns
    // enforcement off in the documented blank-ADAPTER_SECRET dev mode.
    authenticate(ADAPTER_AUTH_SCHEME, optional = env.adapterAuthOptional) {
        get("/status") {
            val result: AdapterStatus = handler.status()
            metrics?.statusObserved(result.pool)
            val requestHost: String = call.request.host().ifBlank { "unknown" }
            call.respond(
                PoolStatusResponse(
                    pool = result.pool,
                    access = result.access ?: handler.defaultAccess(env, requestHost),
                    inventory = result.inventory,
                    capabilities = handler.capabilities(env),
                    metadata = result.metadata,
                    devices = result.devices
                )
            )
        }

        post("/acquire") {
            val request = call.receive<AcquireRequest>()
            val startedAt: Long = System.nanoTime()
            val result: AcquireResult = try {
                handler.acquire(request)
            } catch (error: Exception) {
                metrics?.acquireFailed(Duration.ofNanos(System.nanoTime() - startedAt))
                throw error
            }
            metrics?.acquireCompleted(request.count, result.acquiredCount, Duration.ofNanos(System.nanoTime() - startedAt))

            if (result.acquiredCount == 0) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No devices available"))
                return@post
            }

            val leaseId = requireNotNull(result.leaseId) {
                "Adapter returned acquiredCount > 0 but leaseId is null — this is a bug in the adapter implementation"
            }

            val requestHost: String = call.request.host().ifBlank { "unknown" }
            val responseAccess: AdapterAccess = (result.access ?: handler.defaultAccess(env, requestHost))
                .replaceUnknownHosts(requestHost)
            call.respond(
                AcquireResponse(
                    leaseId = leaseId,
                    acquiredCount = result.acquiredCount,
                    access = responseAccess,
                    inventory = result.inventory,
                    capabilities = handler.capabilities(env),
                    metadata = result.metadata,
                    devices = result.devices
                )
            )
        }

        delete("/release/{leaseId}") {
            val leaseId = call.parameters["leaseId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing leaseId"))

            val released: Boolean = handler.release(leaseId)
            metrics?.releaseCompleted(released)
            if (released) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "released"))
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to release lease $leaseId")
                )
            }
        }

        post("/leases/{leaseId}/renew") {
            val leaseId = call.parameters["leaseId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing leaseId"))
            val request = call.receive<RenewLeaseRequest>()
            if (request.ttlSeconds <= 0) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ttlSeconds must be positive"))
                return@post
            }

            when (handler.renew(leaseId, request.ttlSeconds)) {
                true -> call.respond(HttpStatusCode.OK, mapOf("status" to "renewed"))
                false -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown lease $leaseId"))
                null -> call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf("error" to "The ${handler.adapterType} adapter does not support lease renewal")
                )
            }
        }

        get("/leases") {
            val leases: List<AdapterLease>? = handler.leases()
            if (leases == null) {
                call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf("error" to "The ${handler.adapterType} adapter does not support listing leases")
                )
            } else {
                call.respond(LeasesResponse(leases = leases))
            }
        }
    }
}

private fun AdapterAccess.replaceUnknownHosts(requestHost: String): AdapterAccess {
    return copy(
        connections = connections.map { connection ->
            if (connection.host == "unknown") {
                connection.copy(host = requestHost)
            } else {
                connection
            }
        }
    )
}
