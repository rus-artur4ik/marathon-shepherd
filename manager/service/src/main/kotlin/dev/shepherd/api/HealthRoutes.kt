package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.common.BuildInfo
import dev.shepherd.domain.FleetSnapshot
import dev.shepherd.protocol.ProviderHealthSummary
import dev.shepherd.protocol.ReadinessResponse
import dev.shepherd.protocol.SessionCounts
import dev.shepherd.protocol.ShepherdHealthResponse
import dev.shepherd.protocol.ShepherdLivenessResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("dev.shepherd.api.HealthRoutes")

/**
 * Three probes with different questions:
 * - `/live`: is the process up? Never touches a dependency.
 * - `/ready`: can this instance serve the API? Checks the database.
 * - `/health`: what does the fleet look like? Reads the provider snapshot; 503 when no
 *   provider is healthy, which is an operational signal rather than a reason to restart.
 */
fun Route.healthRoutes(services: ManagerServices) {
    val version: String = BuildInfo.version

    get("/live") {
        call.respond(HttpStatusCode.OK, ShepherdLivenessResponse(status = "alive", version = version))
    }

    get("/ready") {
        val databaseOk: Boolean = services.stateStore.ping()
        val providerCount: Int = services.providerRegistry.currentConfig().providers.size
        val checks: Map<String, String> = linkedMapOf(
            "database" to if (databaseOk) "ok" else "unavailable",
            "config" to "ok ($providerCount configured provider(s))"
        )
        call.respond(
            if (databaseOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            ReadinessResponse(status = if (databaseOk) "ready" else "not-ready", version = version, checks = checks)
        )
    }

    get("/health") {
        val snapshot: FleetSnapshot = try {
            services.fleetMonitor.snapshot(services.snapshotMaxAge())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Health snapshot failed: {}", error.message)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ShepherdHealthResponse(
                    status = "unhealthy",
                    version = version,
                    providersTotal = 0,
                    providersHealthy = 0,
                    providers = emptyList()
                )
            )
            return@get
        }

        val statuses = snapshot.providers
        val healthy = statuses.count { it.isHealthy }
        val status = when {
            statuses.isEmpty() -> "unhealthy"
            healthy == statuses.size -> "healthy"
            healthy > 0 -> "degraded"
            else -> "unhealthy"
        }
        val statusCode = if (healthy > 0) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable

        call.respond(
            statusCode,
            ShepherdHealthResponse(
                status = status,
                version = version,
                providersTotal = statuses.size,
                providersHealthy = healthy,
                providers = statuses.map { provider ->
                    ProviderHealthSummary(
                        name = provider.name,
                        status = if (provider.isHealthy) "HEALTHY" else "UNREACHABLE",
                        available = provider.pool.available,
                        busy = provider.pool.busy,
                        total = provider.pool.total,
                        error = provider.error
                    )
                },
                sessions = SessionCounts(
                    pending = snapshot.sessions.pending,
                    ready = snapshot.sessions.ready,
                    allocatedDevices = snapshot.sessions.allocatedDevices
                ),
                checkedAt = snapshot.takenAt.toString()
            )
        )
    }
}
