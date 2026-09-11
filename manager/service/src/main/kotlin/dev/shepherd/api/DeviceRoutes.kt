package dev.shepherd.api

import dev.shepherd.api.dto.toDto
import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.FleetSnapshot
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.devices.DeviceCatalog
import dev.shepherd.domain.devices.DeviceView
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.model.ApiSelector
import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.MaintenanceRequest
import dev.shepherd.protocol.StatusResponse
import io.ktor.http.Parameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Duration

fun Route.deviceRoutes(fleetMonitor: FleetMonitor, deviceCatalog: DeviceCatalog, snapshotMaxAge: () -> Duration) {
    route("/api/v1/devices") {
        get {
            call.actor().requireRole(*READER_ROLES)
            // The background snapshot answers by default; `?refresh=true` polls every adapter now.
            val refresh: Boolean = call.request.queryParameters["refresh"].toBoolean()
            val snapshot: FleetSnapshot = if (refresh) fleetMonitor.refresh() else fleetMonitor.snapshot(snapshotMaxAge())
            val filter = DeviceFilter.from(call.request.queryParameters)
            val statuses = snapshot.providers

            call.respond(
                DevicesResponse(
                    providers = statuses.map { it.toDto() },
                    totalAvailable = statuses.sumOf { it.pool.available },
                    totalBusy = statuses.sumOf { it.pool.busy },
                    devices = deviceCatalog.list(snapshotMaxAge()).filter(filter::matches).map { device -> device.toDto() }
                )
            )
        }

        get("/{id}") {
            call.actor().requireRole(*READER_ROLES)
            val id: String = call.pathParameter("id")
            val device = deviceCatalog.find(id, snapshotMaxAge()) ?: throw ResourceNotFoundException("Device $id not found")
            call.respond(device.toDto())
        }

        put("/{id}/maintenance") {
            val actor = call.actor().requireRole(Role.ADMIN)
            val body: String = call.receiveText()
            val request: MaintenanceRequest = if (body.isBlank()) MaintenanceRequest() else dev.shepherd.ApiJson.decodeFromString(body)
            call.respond(deviceCatalog.enterMaintenance(actor, call.pathParameter("id"), request.reason, snapshotMaxAge()).toDto())
        }

        delete("/{id}/maintenance") {
            val actor = call.actor().requireRole(Role.ADMIN)
            val cleared: Boolean = deviceCatalog.leaveMaintenance(actor, call.pathParameter("id"))
            call.respond(StatusResponse(status = if (cleared) "maintenance cleared" else "not in maintenance"))
        }
    }
}

/** Query filters for the device list: `state`, `provider`, `deviceType`, `api` and repeatable `label=key=value`. */
private data class DeviceFilter(
    val state: String?,
    val provider: String?,
    val deviceType: String?,
    val api: ApiSelector?,
    val labels: Map<String, String>
) {
    fun matches(device: DeviceView): Boolean = (state == null || device.state == state) &&
        (provider == null || device.provider == provider) &&
        (deviceType == null || device.deviceType == deviceType) &&
        (api == null || api.matches(device.apiLevel)) &&
        labels.all { (key, value) -> device.labels[key] == value }

    companion object {
        fun from(parameters: Parameters): DeviceFilter = DeviceFilter(
            state = parameters["state"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            provider = parameters["provider"]?.trim()?.takeIf { it.isNotEmpty() },
            deviceType = parameters["deviceType"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            api = parameters["api"]?.takeIf { it.isNotBlank() }?.let(ApiSelector::parse),
            labels = parameters.getAll("label").orEmpty().associate { label ->
                val separator = label.indexOf('=')
                require(separator > 0) { "label filters look like label=key=value, got '$label'" }
                label.substring(0, separator) to label.substring(separator + 1)
            }
        )
    }
}
