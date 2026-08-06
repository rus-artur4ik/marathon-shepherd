package dev.shepherd.adapter.api

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import java.security.MessageDigest

const val ADAPTER_AUTH_SCHEME = "adapter-bearer"

/**
 * Whether protected routes should admit callers that present no credentials at all.
 *
 * True exactly when [AdapterEnv.authEnabled] is false: the bearer provider challenges a
 * credential-less request with 401 before the verifier ever runs, so "no secret
 * configured" has to be expressed by making authentication optional, not by accepting
 * every token.
 */
val AdapterEnv.adapterAuthOptional: Boolean get() = !authEnabled

/**
 * Installs bearer token auth for any adapter. All routes except `/health` are protected.
 *
 * The scheme is registered unconditionally, even when [secret] is blank. Routes declare
 * `authenticate(ADAPTER_AUTH_SCHEME, ...)` at construction time, so leaving the provider
 * unregistered would break every protected route instead of running the adapter
 * unauthenticated as documented. Callers opt out of *enforcement* by passing
 * `optional = true` — see [adapterAuthOptional].
 *
 * Blank secrets are for local development only. In any shared environment ADAPTER_SECRET
 * must be set — the adapter proxies raw adb, so an unauthenticated one is full device
 * control for anyone who can reach the port.
 */
fun Application.configureAdapterAuth(secret: String) {
    val authDisabled = secret.isBlank()
    if (authDisabled) {
        environment.log.warn("ADAPTER_SECRET is not set — adapter is running WITHOUT authentication")
    }

    install(Authentication) {
        bearer(ADAPTER_AUTH_SCHEME) {
            authenticate { credential ->
                when {
                    authDisabled -> UserIdPrincipal("anonymous")
                    constantTimeEquals(credential.token, secret) -> UserIdPrincipal("manager")
                    else -> null
                }
            }
        }
    }
}

/**
 * Compares two tokens without leaking their common prefix length through timing.
 *
 * Both sides are hashed first so the comparison runs over fixed-length digests and the
 * length of [expected] cannot be inferred from timing either.
 */
private fun constantTimeEquals(actual: String, expected: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val actualDigest = digest.digest(actual.toByteArray(Charsets.UTF_8))
    val expectedDigest = digest.digest(expected.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(actualDigest, expectedDigest)
}
