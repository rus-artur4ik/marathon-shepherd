package dev.shepherd.adapter.api

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer

const val ADAPTER_AUTH_SCHEME = "adapter-bearer"

/**
 * Installs bearer token auth for any adapter.
 * All routes except /health are protected.
 *
 * If [secret] is blank, auth is disabled — useful for local dev.
 * In production ADAPTER_SECRET must always be set.
 */
fun Application.configureAdapterAuth(secret: String) {
    if (secret.isBlank()) {
        environment.log.warn("ADAPTER_SECRET is not set — adapter is running WITHOUT authentication")
        return
    }

    install(Authentication) {
        bearer(ADAPTER_AUTH_SCHEME) {
            authenticate { credential ->
                if (credential.token == secret) UserIdPrincipal("manager") else null
            }
        }
    }
}
