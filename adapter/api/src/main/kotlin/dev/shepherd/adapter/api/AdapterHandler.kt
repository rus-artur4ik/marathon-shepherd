package dev.shepherd.adapter.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

data class AdapterStatus(
    val pool: AdapterPool,
    val access: AdapterAccess? = null,
    val inventory: List<AdapterDeviceProfile> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
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
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Single override point for adapter-specific logic.
 * Subclasses implement the four service-specific operations;
 * HTTP wiring is handled once by [Route.adapterRoutes].
 */
abstract class AdapterHandler(val adapterType: String, val version: String = "0.1.0") {
    abstract suspend fun isHealthy(): Boolean
    abstract suspend fun status(): AdapterStatus

    /** Return [AcquireResult] with acquiredCount == 0 when no devices are available. */
    abstract suspend fun acquire(request: AcquireRequest): AcquireResult

    /** Return true on success, false on failure. */
    abstract suspend fun release(leaseId: String): Boolean

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

/** Registers all four adapter endpoints. Call this once from [startAdapterServer]. */
fun Route.adapterRoutes(handler: AdapterHandler, env: AdapterEnv) {
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
            val requestHost: String = call.request.host().ifBlank { "unknown" }
            call.respond(
                PoolStatusResponse(
                    pool = result.pool,
                    access = result.access ?: handler.defaultAccess(env, requestHost),
                    inventory = result.inventory,
                    capabilities = handler.capabilities(env),
                    metadata = result.metadata
                )
            )
        }

        post("/acquire") {
            val request = call.receive<AcquireRequest>()
            val result: AcquireResult = handler.acquire(request)

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
                    metadata = result.metadata
                )
            )
        }

        delete("/release/{leaseId}") {
            val leaseId = call.parameters["leaseId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing leaseId"))

            if (handler.release(leaseId)) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "released"))
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to release lease $leaseId")
                )
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
