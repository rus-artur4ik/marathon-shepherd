package dev.shepherd.api

import dev.shepherd.api.dto.toDto
import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.FleetSnapshot
import dev.shepherd.protocol.DevicesResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Duration

fun Route.deviceRoutes(fleetMonitor: FleetMonitor, snapshotMaxAge: () -> Duration) {
    route("/api/v1/devices") {
        get {
            call.actor().requireRole(*READER_ROLES)
            // The background snapshot answers by default; `?refresh=true` polls every adapter now.
            val refresh: Boolean = call.request.queryParameters["refresh"].toBoolean()
            val snapshot: FleetSnapshot = if (refresh) fleetMonitor.refresh() else fleetMonitor.snapshot(snapshotMaxAge())
            val statuses = snapshot.providers

            call.respond(
                DevicesResponse(
                    providers = statuses.map { it.toDto() },
                    totalAvailable = statuses.sumOf { it.pool.available },
                    totalBusy = statuses.sumOf { it.pool.busy }
                )
            )
        }
    }
}
