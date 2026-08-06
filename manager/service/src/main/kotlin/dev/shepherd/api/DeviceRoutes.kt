package dev.shepherd.api

import dev.shepherd.api.dto.DevicesResponse
import dev.shepherd.api.dto.toDto
import dev.shepherd.domain.DeviceAllocator
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.deviceRoutes(deviceAllocator: DeviceAllocator) {
    route("/api/v1/devices") {
        get {
            val statuses = deviceAllocator.getProviderStatuses()

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
