package dev.shepherd.api

import dev.shepherd.api.dto.CreateSessionRequest
import dev.shepherd.api.dto.toResponse
import dev.shepherd.domain.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.sessionRoutes(sessionManager: SessionManager) {
    route("/api/v1/sessions") {
        post {
            val request = call.receive<CreateSessionRequest>()

            val session = sessionManager.createSession(
                requestedDevices = request.devices,
                apiLevel = request.apiLevel,
                ttlSeconds = request.ttlSeconds,
                deviceType = request.deviceType
            )

            call.respond(HttpStatusCode.Created, session.toResponse())
        }

        get {
            val statusFilter = call.request.queryParameters["status"]
            val sessions = sessionManager.listSessions(statusFilter)
            call.respond(sessions.map { it.toResponse() })
        }

        get("/{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))

            val session = sessionManager.getSession(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))

            call.respond(session.toResponse())
        }

        delete("/{id}") {
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))

            val released = sessionManager.releaseSession(id)
            if (released) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "released"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            }
        }
    }
}
