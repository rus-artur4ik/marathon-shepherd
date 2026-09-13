package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.api.dto.toResponse
import dev.shepherd.domain.errors.AccessDeniedException
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.model.AuthConfig
import dev.shepherd.infra.auth.SignInFailure
import dev.shepherd.infra.auth.StartedWebSession
import dev.shepherd.infra.auth.UserRecord
import dev.shepherd.infra.auth.UserSource
import dev.shepherd.protocol.AuthMethodsResponse
import dev.shepherd.protocol.AuthProviderDto
import dev.shepherd.protocol.ChangePasswordWithLoginRequest
import dev.shepherd.protocol.LoginRequest
import dev.shepherd.protocol.TokenLoginRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Duration

/** Signing in and out. Everything here works without credentials; that is what it is for. */
fun Route.publicAuthRoutes(services: ManagerServices) {
    route("/api/v1/auth") {
        get("/methods") {
            val config: AuthConfig = services.authConfig()
            call.respond(
                AuthMethodsResponse(
                    local = config.local.enabled,
                    ldap = config.ldap != null,
                    signInError = call.takeSignInError(config),
                    providers = config.oidc.map { provider ->
                        AuthProviderDto(id = provider.id, displayName = provider.label, loginUrl = "/auth/oidc/${provider.id}/login")
                    }
                )
            )
        }

        /** Starts a browser session. */
        post("/login") {
            val request = call.receive<LoginRequest>()
            val user: UserRecord = services.signIn.withPassword(request.username, request.password, call.address())
            call.startBrowserSession(services, user, method = user.passwordMethod())
        }

        /** Signs in with a password and returns a personal token, for mshctl and scripts. */
        post("/tokens") {
            val request = call.receive<TokenLoginRequest>()
            val user: UserRecord = services.signIn.withPassword(request.username, request.password, call.address())
            if (user.mustChangePassword) {
                throw AccessDeniedException("Choose a new password first: mshctl passwd, or sign in to the web UI")
            }
            val actor = services.accounts.toActor(user, call.address())
            call.respond(
                HttpStatusCode.Created,
                services.accounts.issueToken(actor, user, request.name, request.expiresInDays).toResponse()
            )
        }

        /** Changes a password without a session — a temporary password, say. */
        post("/password") {
            val request = call.receive<ChangePasswordWithLoginRequest>()
            services.signIn.changePassword(request.username, request.currentPassword, request.newPassword, call.address())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/auth/oidc/{provider}") {
        get("/login") {
            val target: String = try {
                services.signIn.startOidc(call.pathParameter("provider"), call.request.queryParameters["returnTo"])
            } catch (failure: SignInFailure) {
                // An unreachable or misconfigured provider: back to the sign-in page, which says why.
                call.setSignInError(failure.message ?: "Sign-in failed", services.authConfig())
                UI_SIGN_IN_PATH
            }
            call.respondRedirect(target)
        }

        get("/callback") {
            val parameters = call.request.queryParameters
            val provider: String = call.pathParameter("provider")
            try {
                val (user, returnTo) = services.signIn.withOidc(
                    providerId = provider,
                    code = parameters["code"],
                    state = parameters["state"],
                    error = parameters["error_description"] ?: parameters["error"],
                    address = call.address()
                )
                val started: StartedWebSession = services.signIn.startSession(
                    user,
                    call.address(),
                    call.request.userAgent(),
                    method = "oidc:$provider"
                )
                call.setSessionCookie(
                    started.cookieValue,
                    services.authConfig(),
                    Duration.ofHours(services.authConfig().sessions.maxLifetimeHours)
                )
                call.respondRedirect(returnTo)
            } catch (failure: SignInFailure) {
                // Back to the sign-in page, which shows the reason. It travels in a short-lived cookie
                // rather than the URL, so a crafted link cannot put its own words on that page.
                call.setSignInError(failure.message ?: "Sign-in failed", services.authConfig())
                call.respondRedirect(UI_SIGN_IN_PATH)
            }
        }
    }
}

/** The browser session itself: who is signed in, and signing out. */
fun Route.browserSessionRoutes(services: ManagerServices) {
    get("/api/v1/auth/session") {
        val web: WebPrincipal = call.principal<WebPrincipal>()
            ?: throw ResourceNotFoundException("This request carries no browser session; sign in with POST /api/v1/auth/login")
        call.respond(web.session.session.toResponse(web.session.user, services.accounts))
    }

    post("/api/v1/auth/logout") {
        call.principal<WebPrincipal>()?.let { web -> services.signIn.endSession(web.session, call.address()) }
        call.clearSessionCookie(services.authConfig())
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun ApplicationCall.startBrowserSession(services: ManagerServices, user: UserRecord, method: String) {
    val started: StartedWebSession = services.signIn.startSession(user, address(), request.userAgent(), method)
    setSessionCookie(started.cookieValue, services.authConfig(), Duration.ofHours(services.authConfig().sessions.maxLifetimeHours))
    respond(started.toResponse(services.accounts))
}

private fun UserRecord.passwordMethod(): String = if (source == UserSource.LDAP) "ldap" else "password"

internal fun ApplicationCall.address(): String = request.origin.remoteHost
