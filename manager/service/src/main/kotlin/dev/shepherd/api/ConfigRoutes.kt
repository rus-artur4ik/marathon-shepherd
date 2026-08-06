package dev.shepherd.api

import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.model.ShepherdConfig
import dev.shepherd.domain.model.redactSecrets
import dev.shepherd.domain.model.restoreRedactedSecrets
import dev.shepherd.domain.provider.ProviderRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.configRoutes(providerRegistry: ProviderRegistry, sessionManager: SessionManager) {
    route("/api/v1/config") {
        get {
            call.respond(providerRegistry.currentConfig().redactSecrets())
        }

        put {
            val currentConfig = providerRegistry.currentConfig()
            // Adapter secrets are redacted on the way out, so a client that read the
            // config, edited it and sent it back must not have those placeholders
            // persisted as real credentials.
            val newConfig = call.receive<ShepherdConfig>().restoreRedactedSecrets(currentConfig)

            val currentProviderNames = currentConfig.providers.map { it.name }.toSet()
            val newProviderNames = newConfig.providers.map { it.name }.toSet()
            val removedProviders = currentProviderNames - newProviderNames

            val blockedProviders = removedProviders.filter { providerName ->
                sessionManager.hasActiveSessionsForProvider(providerName)
            }
            if (blockedProviders.isNotEmpty()) {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf(
                        "error" to "Cannot remove providers with active sessions: " +
                            blockedProviders.joinToString(", ")
                    )
                )
                return@put
            }

            call.respond(HttpStatusCode.OK, providerRegistry.updateConfig(newConfig).redactSecrets())
        }

        post("/reload") {
            call.respond(providerRegistry.reloadConfig().redactSecrets())
        }
    }
}
