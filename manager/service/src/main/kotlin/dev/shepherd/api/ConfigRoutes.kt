package dev.shepherd.api

import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.audit.AuditActions
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.domain.model.ShepherdConfig
import dev.shepherd.domain.model.redactSecrets
import dev.shepherd.domain.model.restoreRedactedSecrets
import dev.shepherd.domain.provider.ProviderRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Provider configuration. Admin only: it names every adapter the manager can reach. */
fun Route.configRoutes(providerRegistry: ProviderRegistry, sessionManager: SessionManager, audit: AuditTrail) {
    route("/api/v1/config") {
        get {
            call.actor().requireRole(Role.ADMIN)
            call.respond(providerRegistry.currentConfig().redactSecrets())
        }

        put {
            val actor = call.actor().requireRole(Role.ADMIN)
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
                throw ConflictException("Cannot remove providers with active sessions: " + blockedProviders.joinToString(", "))
            }

            val applied = providerRegistry.updateConfig(newConfig)
            audit.record(actor, AuditActions.CONFIG_UPDATE, details = mapOf("providers" to providerNames(applied)))
            call.respond(HttpStatusCode.OK, applied.redactSecrets())
        }

        post("/reload") {
            val actor = call.actor().requireRole(Role.ADMIN)
            val reloaded = providerRegistry.reloadConfig()
            audit.record(actor, AuditActions.CONFIG_RELOAD, details = mapOf("providers" to providerNames(reloaded)))
            call.respond(reloaded.redactSecrets())
        }
    }
}

private fun providerNames(config: ShepherdConfig): String = config.providers.joinToString(",") { provider -> provider.name }
