package dev.shepherd.domain.audit

import dev.shepherd.domain.auth.Actor

enum class AuditOutcome {
    SUCCESS,
    DENIED,
    FAILED;

    val wireName: String get() = name.lowercase()
}

/** Stable action names; clients filter the audit log by prefix, e.g. `session.`. */
object AuditActions {
    const val SESSION_CREATE = "session.create"
    const val SESSION_RELEASE = "session.release"
    const val SESSION_EXPIRE = "session.expire"
    const val SESSION_FAIL = "session.fail"
    const val CLIENT_CREATE = "client.create"
    const val CLIENT_UPDATE = "client.update"
    const val CLIENT_ROTATE = "client.rotate"
    const val CLIENT_REVOKE = "client.revoke"
    const val CONFIG_UPDATE = "config.update"
    const val CONFIG_RELOAD = "config.reload"
}

/** Durable record of who did what. Recording must never fail the operation it describes. */
interface AuditTrail {
    suspend fun record(
        actor: Actor,
        action: String,
        target: String? = null,
        outcome: AuditOutcome = AuditOutcome.SUCCESS,
        details: Map<String, String> = emptyMap()
    )

    companion object {
        val NONE: AuditTrail = object : AuditTrail {
            override suspend fun record(
                actor: Actor,
                action: String,
                target: String?,
                outcome: AuditOutcome,
                details: Map<String, String>
            ) = Unit
        }
    }
}
