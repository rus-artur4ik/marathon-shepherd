package dev.shepherd.api

import dev.shepherd.ApiJson
import dev.shepherd.ManagerServices
import dev.shepherd.protocol.DeviceQuery
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

fun Route.deviceRoutes(services: ManagerServices) {
    route("/api/v1/devices") {
        get {
            call.respond(call.shepherdApi(services).listDevices(deviceQuery(call.request.queryParameters)))
        }

        get("/{id}") {
            call.respond(call.shepherdApi(services).getDevice(call.pathParameter("id")))
        }

        put("/{id}/maintenance") {
            val api: LocalShepherdApi = call.shepherdApi(services)
            val body: String = call.receiveText()
            val request: MaintenanceRequest = if (body.isBlank()) MaintenanceRequest() else ApiJson.decodeFromString(body)
            call.respond(api.enterMaintenance(call.pathParameter("id"), request.reason))
        }

        delete("/{id}/maintenance") {
            val cleared: Boolean = call.shepherdApi(services).leaveMaintenance(call.pathParameter("id"))
            call.respond(StatusResponse(status = if (cleared) "maintenance cleared" else "not in maintenance"))
        }
    }
}

/** Query filters for the device list: `state`, `provider`, `deviceType`, `api`, repeatable `label=key=value` and `refresh`. */
private fun deviceQuery(parameters: Parameters): DeviceQuery = DeviceQuery(
    state = parameters["state"],
    provider = parameters["provider"],
    deviceType = parameters["deviceType"],
    api = parameters["api"],
    labels = parameters.getAll("label").orEmpty().associate { label ->
        val separator = label.indexOf('=')
        require(separator > 0) { "label filters look like label=key=value, got '$label'" }
        label.substring(0, separator) to label.substring(separator + 1)
    },
    refresh = parameters["refresh"].toBoolean()
)
