package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.model.AuthConfig
import dev.shepherd.infra.auth.Accounts
import dev.shepherd.infra.auth.ApiKeys
import dev.shepherd.infra.auth.ResolvedWebSession
import dev.shepherd.infra.auth.SignIn
import dev.shepherd.infra.auth.UserRecord
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.time.Duration

/** Name of the provider that accepts the web UI's session cookie on the `/api/v1` routes. */
const val WEB_AUTH: String = "web-session"
const val SESSION_COOKIE: String = "msh_session"
const val CSRF_HEADER: String = "X-CSRF-Token"

/** A person signed in through the browser. */
data class WebPrincipal(val actor: Actor, val session: ResolvedWebSession)

/** Accepts a session cookie. Without one, or with a stale one, it steps aside so the bearer provider answers 401. */
class WebSessionAuthenticationProvider(
    private val signIn: SignIn,
    private val accounts: Accounts
) : AuthenticationProvider(Config(WEB_AUTH)) {
    private class Config(name: String) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val cookie: String = context.call.request.cookies[SESSION_COOKIE] ?: return
        val session: ResolvedWebSession = signIn.resolveSession(cookie) ?: return
        context.principal(WebPrincipal(accounts.toActor(session.user, context.call.request.origin.remoteHost), session))
    }
}

/**
 * Two rules for requests that ride on a browser session. Changes must carry the session's CSRF
 * token in [CSRF_HEADER], which another site cannot read. And someone who still has a temporary
 * password may only choose a new one.
 */
val BrowserSessionGuard = createRouteScopedPlugin("BrowserSessionGuard") {
    on(AuthenticationChecked) { call ->
        val web: WebPrincipal = call.principal<WebPrincipal>() ?: return@on
        if (call.request.httpMethod !in SAFE_METHODS) {
            val presented: String? = call.request.headers[CSRF_HEADER]
            if (presented == null || !ApiKeys.constantTimeEquals(presented, web.session.session.csrfToken)) {
                call.respondError(
                    HttpStatusCode.Forbidden,
                    "Missing or wrong $CSRF_HEADER header; take the token from GET /api/v1/auth/session"
                )
                return@on
            }
        }
        if (web.session.user.mustChangePassword && call.request.path() !in PASSWORD_CHANGE_PATHS) {
            call.respondError(HttpStatusCode.Forbidden, "Choose a new password first: POST /api/v1/me/password")
        }
    }
}

/** Headers every answer carries; the web UI adds a Content-Security-Policy on top. */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Referrer-Policy", "same-origin")
        call.response.headers.append("X-Frame-Options", "DENY")
    }
}

internal fun ApplicationCall.setSessionCookie(value: String, config: AuthConfig, maxAge: Duration) {
    val attributes: List<String> = buildList {
        add("$SESSION_COOKIE=$value")
        add("Path=/")
        add("Max-Age=${maxAge.seconds.coerceAtLeast(0)}")
        add("HttpOnly")
        add("SameSite=Lax")
        if (config.secureCookies) add("Secure")
    }
    response.headers.append(HttpHeaders.SetCookie, attributes.joinToString("; "))
}

internal fun ApplicationCall.clearSessionCookie(config: AuthConfig) = setSessionCookie("", config, Duration.ZERO)

/** The person making this request, whether through a browser session or a personal token. */
internal suspend fun ApplicationCall.currentUser(services: ManagerServices): UserRecord {
    principal<WebPrincipal>()?.let { web -> return web.session.user }
    val actor: Actor = actor()
    require(actor.id.startsWith(Accounts.USER_ID_PREFIX)) { "API clients have no password or personal tokens; this is for people" }
    return services.accounts.getUser(actor.id)
}

private val SAFE_METHODS: Set<HttpMethod> = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)
private val PASSWORD_CHANGE_PATHS: Set<String> = setOf("/api/v1/auth/session", "/api/v1/auth/logout", "/api/v1/me/password", "/api/v1/me")
