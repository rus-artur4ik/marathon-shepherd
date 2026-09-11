package dev.shepherd.api

import dev.shepherd.ApiJson
import dev.shepherd.api.dto.toResponse
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionOptions
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

fun Route.sessionRoutes(sessionManager: SessionManager) {
    route("/api/v1/sessions") {
        post {
            val actor: Actor = call.actor().requireRole(*HOLDER_ROLES)
            val request = call.receive<CreateSessionRequest>()

            val session = sessionManager.createSession(
                requestedDevices = request.resolvedMaxDevices(),
                api = request.resolvedApi(),
                ttlSeconds = request.ttlSeconds,
                deviceType = request.deviceType,
                actor = actor,
                options = SessionOptions(
                    name = request.name,
                    metadata = request.metadata,
                    priority = request.priority,
                    idleTimeoutSeconds = request.idleTimeoutSeconds,
                    labels = request.labels,
                    deviceIds = request.deviceIds
                )
            )
            val queuePosition = sessionManager.getQueuePosition(session.id)
            call.respond(HttpStatusCode.Created, session.toResponse(queuePosition = queuePosition))
        }

        get {
            val actor: Actor = call.actor().requireRole(*READER_ROLES)
            val statusFilter = call.request.queryParameters["status"]
            // `owner=me` selects the caller's own sessions; any other value matches owner names.
            val owner: String? = call.request.queryParameters["owner"]?.trim()?.takeIf { value -> value.isNotEmpty() }
            val sessions: List<Session> = if (owner == OWNER_ME) {
                sessionManager.listSessions(statusFilter, ownerId = actor.id)
            } else {
                sessionManager.listSessions(statusFilter).filter { session -> owner == null || session.ownerName == owner }
            }
            call.respond(sessions.map { it.toResponse() })
        }

        get("/{id}") {
            call.actor().requireRole(*READER_ROLES)
            val session = sessionManager.getSession(call.pathParameter("id"))
                ?: throw ResourceNotFoundException("Session not found")

            val queuePosition = sessionManager.getQueuePosition(session.id)
            call.respond(session.toResponse(queuePosition = queuePosition))
        }

        post("/{id}/wait") {
            val actor: Actor = call.actor().requireRole(*HOLDER_ROLES)
            val id: String = call.pathParameter("id")
            if (sessionManager.getSession(id) == null) {
                throw ResourceNotFoundException("Session not found")
            }
            // The body is optional; an empty one waits for the default timeout.
            val body: String = call.receiveText()
            val request: WaitSessionRequest = if (body.isBlank()) WaitSessionRequest() else ApiJson.decodeFromString(body)
            val session = sessionManager.waitForSession(id, request.timeoutSeconds, actor)
            val queuePosition = sessionManager.getQueuePosition(session.id)
            call.respond(session.toResponse(queuePosition = queuePosition))
        }

        post("/{id}/heartbeat") {
            val actor: Actor = call.actor().requireRole(*HOLDER_ROLES)
            val session = sessionManager.heartbeat(call.pathParameter("id"), actor)
            call.respond(session.toResponse(queuePosition = sessionManager.getQueuePosition(session.id)))
        }

        post("/{id}/extend") {
            val actor: Actor = call.actor().requireRole(*HOLDER_ROLES)
            val request = call.receive<ExtendSessionRequest>()
            val session = sessionManager.extendSession(call.pathParameter("id"), request.ttlSeconds, actor)
            call.respond(session.toResponse(queuePosition = sessionManager.getQueuePosition(session.id)))
        }

        delete("/{id}") {
            val actor: Actor = call.actor().requireRole(*HOLDER_ROLES)
            if (!sessionManager.releaseSession(call.pathParameter("id"), actor)) {
                throw ResourceNotFoundException("Session not found")
            }
            call.respond(HttpStatusCode.OK, StatusResponse(status = "released"))
        }
    }
}

private const val OWNER_ME: String = "me"
