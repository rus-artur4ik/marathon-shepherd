package dev.shepherd.api

import dev.shepherd.api.dto.CreateSessionRequest
import dev.shepherd.api.dto.WaitSessionRequest
import dev.shepherd.api.dto.toResponse
import dev.shepherd.domain.SessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.sessionRoutes(sessionManager: SessionManager) {
    route("/api/v1/sessions") {
        post {
            val request = call.receive<CreateSessionRequest>()

            val session = sessionManager.createSession(
                requestedDevices = request.resolvedMaxDevices(),
                api = request.resolvedApi(),
                ttlSeconds = request.ttlSeconds,
                deviceType = request.deviceType
            )
            val queuePosition = sessionManager.getQueuePosition(session.id)
            call.respond(HttpStatusCode.Created, session.toResponse(queuePosition = queuePosition))
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

            val queuePosition = sessionManager.getQueuePosition(session.id)
            call.respond(session.toResponse(queuePosition = queuePosition))
        }

        post("/{id}/wait") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session id"))
            if (sessionManager.getSession(id) == null) {
                return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            }
            val request = call.receive<WaitSessionRequest>()
            val session = sessionManager.waitForSession(id, request.timeoutSeconds)
            val queuePosition = sessionManager.getQueuePosition(session.id)
            call.respond(session.toResponse(queuePosition = queuePosition))
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
