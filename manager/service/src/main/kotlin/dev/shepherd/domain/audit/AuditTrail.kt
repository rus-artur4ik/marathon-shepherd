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
    const val SESSION_EXTEND = "session.extend"
    const val DEVICE_MAINTENANCE_ENABLE = "device.maintenance.enable"
    const val DEVICE_MAINTENANCE_DISABLE = "device.maintenance.disable"
    const val PROVIDER_REGISTER = "provider.register"
    const val PROVIDER_DEREGISTER = "provider.deregister"
    const val LEASE_RECLAIM = "lease.reclaim"
    const val CLIENT_CREATE = "client.create"
    const val CLIENT_UPDATE = "client.update"
    const val CLIENT_ROTATE = "client.rotate"
    const val CLIENT_REVOKE = "client.revoke"
    const val CONFIG_UPDATE = "config.update"
    const val CONFIG_RELOAD = "config.reload"
    const val AUTH_LOGIN = "auth.login"
    const val AUTH_LOGOUT = "auth.logout"
    const val USER_CREATE = "user.create"
    const val USER_UPDATE = "user.update"
    const val USER_DISABLE = "user.disable"
    const val USER_ENABLE = "user.enable"
    const val USER_PASSWORD_CHANGE = "user.password.change"
    const val USER_PASSWORD_RESET = "user.password.reset"
    const val TOKEN_CREATE = "token.create"
    const val TOKEN_REVOKE = "token.revoke"
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
