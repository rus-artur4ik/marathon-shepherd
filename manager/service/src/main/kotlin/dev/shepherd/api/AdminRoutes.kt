package dev.shepherd.api

import dev.shepherd.api.dto.toDomain
import dev.shepherd.api.dto.toDto
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.auth.Role
import dev.shepherd.infra.auth.AccessControl
import dev.shepherd.infra.auth.ClientRecord
import dev.shepherd.infra.auth.IssuedKey
import dev.shepherd.protocol.ClientDto
import dev.shepherd.protocol.ClientKeyResponse
import dev.shepherd.protocol.CreateClientRequest
import dev.shepherd.protocol.UpdateClientRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** API clients and their keys. Admin only. */
fun Route.adminRoutes(accessControl: AccessControl, sessionManager: SessionManager) {
    fun ClientRecord.toDto(): ClientDto = toDto(accessControl.effectiveQuota(this))
    fun IssuedKey.toResponse(): ClientKeyResponse = ClientKeyResponse(client = client.toDto(), apiKey = apiKey)

    route("/api/v1/admin/clients") {
        get {
            call.actor().requireRole(Role.ADMIN)
            val includeRevoked: Boolean = call.request.queryParameters["includeRevoked"].toBoolean()
            call.respond(accessControl.listClients(includeRevoked).map { client -> client.toDto() })
        }

        post {
            val actor = call.actor().requireRole(Role.ADMIN)
            val request = call.receive<CreateClientRequest>()
            val issued = accessControl.createClient(
                actor = actor,
                name = request.name,
                role = Role.parse(request.role),
                description = request.description,
                quota = request.quota.toDomain()
            )
            call.respond(HttpStatusCode.Created, issued.toResponse())
        }

        get("/{id}") {
            call.actor().requireRole(Role.ADMIN)
            call.respond(accessControl.getClient(call.pathParameter("id")).toDto())
        }

        patch("/{id}") {
            val actor = call.actor().requireRole(Role.ADMIN)
            val request = call.receive<UpdateClientRequest>()
            val updated = accessControl.updateClient(
                actor = actor,
                id = call.pathParameter("id"),
                role = request.role?.let(Role::parse),
                description = request.description,
                quota = request.quota?.toDomain()
            )
            call.respond(updated.toDto())
        }

        post("/{id}/rotate") {
            val actor = call.actor().requireRole(Role.ADMIN)
            call.respond(accessControl.rotateKey(actor, call.pathParameter("id")).toResponse())
        }

        delete("/{id}") {
            val actor = call.actor().requireRole(Role.ADMIN)
            val revoked = accessControl.revokeClient(actor, call.pathParameter("id"))
            // Sessions keep running by default so revoking a CI key does not kill builds in flight.
            if (call.request.queryParameters["releaseSessions"].toBoolean()) {
                sessionManager.releaseSessionsOwnedBy(revoked.id, actor)
            }
            call.respond(revoked.toDto())
        }
    }
}
