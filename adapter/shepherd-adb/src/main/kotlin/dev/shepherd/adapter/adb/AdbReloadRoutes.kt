package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.ADAPTER_AUTH_SCHEME
import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.AdapterHandler
import dev.shepherd.adapter.api.PoolStatusResponse
import dev.shepherd.adapter.api.adapterAuthOptional
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Route.adbReloadRoutes(handler: AdapterHandler, env: AdapterEnv, adminService: AdbAdminService) {
    authenticate(ADAPTER_AUTH_SCHEME, optional = env.adapterAuthOptional) {
        post("/adb-reload") {
            when (val outcome = adminService.reloadAdbDaemon()) {
                is AdbReloadOutcome.Busy -> {
                    call.respond(
                        HttpStatusCode.Conflict,
                        AdbReloadBlockedResponse(
                            error = "ADB reload is blocked while leases are active",
                            activeLeaseCount = outcome.activeLeaseIds.size,
                            activeLeaseIds = outcome.activeLeaseIds
                        )
                    )
                }

                is AdbReloadOutcome.Failed -> {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to outcome.message)
                    )
                }

                is AdbReloadOutcome.Success -> {
                    val result = handler.status()
                    val requestHost: String = call.request.host().ifBlank { "unknown" }
                    call.respond(
                        PoolStatusResponse(
                            pool = result.pool,
                            access = result.access ?: handler.defaultAccess(env, requestHost),
                            inventory = result.inventory,
                            capabilities = handler.capabilities(env),
                            metadata = result.metadata + mapOf("adbReload" to "ok")
                        )
                    )
                }
            }
        }
    }
}

@Serializable
private data class AdbReloadBlockedResponse(
    val error: String,
    val activeLeaseCount: Int,
    val activeLeaseIds: List<String>
)
