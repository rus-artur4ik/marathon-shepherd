package dev.shepherd.api

import dev.shepherd.ApiJson
import dev.shepherd.ManagerServices
import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.ExtendSessionRequest
import dev.shepherd.protocol.StatusResponse
import dev.shepherd.protocol.WaitSessionRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.sessionRoutes(services: ManagerServices) {
    route("/api/v1/sessions") {
        post {
            val api: LocalShepherdApi = call.shepherdApi(services)
            call.respond(HttpStatusCode.Created, api.createSession(call.receive<CreateSessionRequest>()))
        }

        get {
            val parameters = call.request.queryParameters
            call.respond(call.shepherdApi(services).listSessions(status = parameters["status"], owner = parameters["owner"]))
        }

        get("/{id}") {
            call.respond(call.shepherdApi(services).getSession(call.pathParameter("id")))
        }

        post("/{id}/wait") {
            val api: LocalShepherdApi = call.shepherdApi(services)
            // The body is optional; an empty one waits for the default timeout.
            val body: String = call.receiveText()
            val request: WaitSessionRequest = if (body.isBlank()) WaitSessionRequest() else ApiJson.decodeFromString(body)
            call.respond(api.waitForSession(call.pathParameter("id"), request.timeoutSeconds))
        }

        post("/{id}/heartbeat") {
            call.respond(call.shepherdApi(services).heartbeat(call.pathParameter("id")))
        }

        post("/{id}/extend") {
            val api: LocalShepherdApi = call.shepherdApi(services)
            val request = call.receive<ExtendSessionRequest>()
            call.respond(api.extendSession(call.pathParameter("id"), request.ttlSeconds))
        }

        delete("/{id}") {
            call.shepherdApi(services).releaseSession(call.pathParameter("id"))
            call.respond(HttpStatusCode.OK, StatusResponse(status = "released"))
        }
    }
}
