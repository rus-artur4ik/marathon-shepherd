package dev.shepherd.api

import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.AccessDeniedException
import dev.shepherd.infra.auth.AccessControl
import dev.shepherd.infra.auth.Accounts
import dev.shepherd.infra.auth.SignIn
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin

/** Name of the bearer provider guarding the `/api/v1` routes and `/mcp`. */
const val API_AUTH: String = "api-key"
private const val REALM: String = "marathon-shepherd"
internal const val BEARER_CHALLENGE: String = "Bearer realm=\"$REALM\""

/** Roles that may read sessions, devices and events. */
internal val READER_ROLES: Array<Role> = arrayOf(Role.ADMIN, Role.USER, Role.VIEWER)

/** Roles that may hold devices. */
internal val HOLDER_ROLES: Array<Role> = arrayOf(Role.ADMIN, Role.USER)

fun Application.configureApiAuth(accessControl: AccessControl, accounts: Accounts, signIn: SignIn) {
    install(Authentication) {
        // A bearer key is a client key, MSH_ADMIN_TOKEN or a person's personal token.
        bearer(API_AUTH) {
            realm = REALM
            authenticate { credential ->
                val actor: Actor? = accessControl.authenticate(credential.token) ?: accounts.authenticateToken(credential.token)
                actor?.copy(origin = request.origin.remoteHost)
            }
        }
        register(WebSessionAuthenticationProvider(signIn, accounts))
    }
}

/** The authenticated caller, by key or browser session. Only valid inside `authenticate(API_AUTH, ...)`. */
fun ApplicationCall.actor(): Actor =
    principal<Actor>() ?: principal<WebPrincipal>()?.actor ?: error("Route is not guarded by authenticate(API_AUTH)")

/** Returns this actor when its role is one of [roles]; otherwise fails with 403. */
fun Actor.requireRole(vararg roles: Role): Actor {
    if (role !in roles) {
        throw AccessDeniedException(
            "This operation needs the ${roles.joinToString(" or ") { allowed -> allowed.wireName }} role; " +
                "'$name' has '${role.wireName}'"
        )
    }
    return this
}

fun ApplicationCall.pathParameter(name: String): String =
    parameters[name]?.takeIf { value -> value.isNotBlank() } ?: throw IllegalArgumentException("Missing path parameter '$name'")
