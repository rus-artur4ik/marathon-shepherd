package dev.shepherd.api

import dev.shepherd.ApiJson
import dev.shepherd.ManagerServices
import dev.shepherd.api.dto.toDomain
import dev.shepherd.api.dto.toDto
import dev.shepherd.domain.auth.Role
import dev.shepherd.infra.auth.UserWithPassword
import dev.shepherd.protocol.CreateUserRequest
import dev.shepherd.protocol.CreatedUserResponse
import dev.shepherd.protocol.PasswordResetResponse
import dev.shepherd.protocol.ResetPasswordRequest
import dev.shepherd.protocol.UpdateUserRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** People who sign in. Admin only. */
fun Route.userAdminRoutes(services: ManagerServices) {
    route("/api/v1/admin/users") {
        get {
            call.actor().requireRole(Role.ADMIN)
            val includeDisabled: Boolean = call.request.queryParameters["includeDisabled"].toBoolean()
            call.respond(services.accounts.listUsers(includeDisabled).map { user -> user.toDto(services.accounts) })
        }

        post {
            val actor = call.actor().requireRole(Role.ADMIN)
            val request = call.receive<CreateUserRequest>()
            val created: UserWithPassword = services.accounts.createLocalUser(
                actor = actor,
                username = request.username,
                password = request.password,
                displayName = request.displayName,
                email = request.email,
                role = Role.parse(request.role),
                quota = request.quota.toDomain()
            )
            call.respond(HttpStatusCode.Created, CreatedUserResponse(created.user.toDto(services.accounts), created.temporaryPassword))
        }

        get("/{id}") {
            call.actor().requireRole(Role.ADMIN)
            call.respond(services.accounts.getUser(call.pathParameter("id")).toDto(services.accounts))
        }

        patch("/{id}") {
            val actor = call.actor().requireRole(Role.ADMIN)
            val request = call.receive<UpdateUserRequest>()
            val updated = services.accounts.updateUser(
                actor = actor,
                id = call.pathParameter("id"),
                displayName = request.displayName,
                email = request.email,
                role = request.role?.let(Role::parse),
                quota = request.quota?.toDomain(),
                active = request.active
            )
            call.respond(updated.toDto(services.accounts))
        }

        post("/{id}/password") {
            val actor = call.actor().requireRole(Role.ADMIN)
            // The body is optional; without a password a temporary one is generated.
            val body: String = call.receiveText()
            val request: ResetPasswordRequest = if (body.isBlank()) ResetPasswordRequest() else ApiJson.decodeFromString(body)
            val reset: UserWithPassword = services.accounts.resetPassword(actor, call.pathParameter("id"), request.password)
            call.respond(PasswordResetResponse(reset.temporaryPassword))
        }

        delete("/{id}") {
            val actor = call.actor().requireRole(Role.ADMIN)
            val id: String = call.pathParameter("id")
            val disabled = services.accounts.updateUser(actor, id, active = false)
            if (call.request.queryParameters["releaseSessions"].toBoolean()) {
                services.sessionManager.releaseSessionsOwnedBy(id, actor)
            }
            call.respond(disabled.toDto(services.accounts))
        }

        get("/{id}/tokens") {
            call.actor().requireRole(Role.ADMIN)
            val id: String = call.pathParameter("id")
            services.accounts.getUser(id)
            call.respond(services.accounts.listTokens(id, includeRevoked = true).map { token -> token.toDto() })
        }

        delete("/{id}/tokens/{tokenId}") {
            val actor = call.actor().requireRole(Role.ADMIN)
            call.respond(services.accounts.revokeToken(actor, call.pathParameter("id"), call.pathParameter("tokenId")).toDto())
        }
    }
}
