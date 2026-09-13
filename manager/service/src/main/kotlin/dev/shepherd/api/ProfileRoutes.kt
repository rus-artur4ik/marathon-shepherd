package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.api.dto.toDto
import dev.shepherd.api.dto.toResponse
import dev.shepherd.infra.auth.UserRecord
import dev.shepherd.protocol.ChangePasswordRequest
import dev.shepherd.protocol.CreateTokenRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** A person's own password and personal tokens. */
fun Route.profileRoutes(services: ManagerServices) {
    route("/api/v1/me") {
        post("/password") {
            val user: UserRecord = call.currentUser(services)
            val request = call.receive<ChangePasswordRequest>()
            // The session that made the change stays signed in; every other one ends.
            val keep: String? = call.principal<WebPrincipal>()?.session?.session?.idHash
            services.accounts.changePassword(user, request.currentPassword, request.newPassword, keepSessionIdHash = keep)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/tokens") {
            val user: UserRecord = call.currentUser(services)
            call.respond(services.accounts.listTokens(user.id).map { token -> token.toDto() })
        }

        post("/tokens") {
            val user: UserRecord = call.currentUser(services)
            val request = call.receive<CreateTokenRequest>()
            call.respond(
                HttpStatusCode.Created,
                services.accounts.issueToken(call.actor(), user, request.name, request.expiresInDays).toResponse()
            )
        }

        delete("/tokens/{id}") {
            val user: UserRecord = call.currentUser(services)
            call.respond(services.accounts.revokeToken(call.actor(), user.id, call.pathParameter("id")).toDto())
        }
    }
}
