package dev.shepherd.api

import dev.shepherd.adapter.api.ProviderRegistrationRequest
import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.provider.ProviderRegistrationService
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.protocol.ProviderInfoDto
import dev.shepherd.protocol.StatusResponse
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Duration

fun Route.providerRoutes(
    providerRegistry: ProviderRegistry,
    registrations: ProviderRegistrationService,
    fleetMonitor: FleetMonitor,
    snapshotMaxAge: () -> Duration
) {
    route("/api/v1/providers") {
        get {
            call.actor().requireRole(*READER_ROLES)
            val health: Map<String, Boolean> = fleetMonitor.snapshot(
                snapshotMaxAge()
            ).providers.associate { status -> status.name to status.isHealthy }
            val static = providerRegistry.currentConfig().providers.map { provider ->
                ProviderInfoDto(
                    name = provider.name,
                    source = SOURCE_STATIC,
                    url = provider.url,
                    accessHost = provider.accessHost,
                    healthy = health[provider.name],
                    active = true
                )
            }
            val registered = providerRegistry.registrations().map { registration ->
                ProviderInfoDto(
                    name = registration.name,
                    source = SOURCE_REGISTERED,
                    url = registration.url,
                    accessHost = registration.accessHost,
                    adapterType = registration.adapterType,
                    healthy = health[registration.name],
                    active = providerRegistry.isFresh(registration),
                    registeredBy = registration.clientName,
                    registeredAt = registration.registeredAt.toString(),
                    lastSeenAt = registration.lastSeenAt.toString()
                )
            }
            call.respond(static + registered)
        }

        /** Called by adapters with a `provider` key, first to join and then as a heartbeat. */
        post("/register") {
            val actor = call.actor().requireRole(Role.PROVIDER, Role.ADMIN)
            val request = call.receive<ProviderRegistrationRequest>()
            call.respond(registrations.register(actor, request))
        }

        delete("/{name}") {
            val actor = call.actor().requireRole(Role.ADMIN)
            registrations.deregister(actor, call.pathParameter("name"))
            call.respond(StatusResponse(status = "deregistered"))
        }
    }
}

private const val SOURCE_STATIC = "static"
private const val SOURCE_REGISTERED = "registered"
