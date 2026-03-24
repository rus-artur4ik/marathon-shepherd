package dev.shepherd.api

import dev.shepherd.domain.DeviceAllocator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ProviderHealthSummary(val name: String, val status: String)

@Serializable
data class ShepherdHealthResponse(
    val status: String,
    val version: String,
    val providersTotal: Int,
    val providersHealthy: Int,
    val providers: List<ProviderHealthSummary>
)

@Serializable
data class ShepherdLivenessResponse(val status: String, val version: String)

fun Route.healthRoutes(deviceAllocator: DeviceAllocator, version: String) {
    get("/live") {
        call.respond(
            HttpStatusCode.OK,
            ShepherdLivenessResponse(
                status = "alive",
                version = version
            )
        )
    }

    get("/health") {
        val statuses = try {
            deviceAllocator.getProviderStatuses()
        } catch (e: Exception) {
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
                providers = statuses.map { p ->
                    ProviderHealthSummary(
                        name = p.name,
                        status = if (p.isHealthy) "HEALTHY" else "UNREACHABLE"
                    )
                }
            )
        )
    }
}
