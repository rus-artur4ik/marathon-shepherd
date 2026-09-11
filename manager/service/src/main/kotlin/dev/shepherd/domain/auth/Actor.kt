package dev.shepherd.domain.auth

import dev.shepherd.domain.model.Session

enum class Role {
    /** Everything, including client management and configuration. */
    ADMIN,

    /** Request, hold and release devices; read the fleet. */
    USER,

    /** Read-only access to sessions, devices and events. */
    VIEWER,

    /** An adapter that registers itself with the manager. */
    PROVIDER;

    val wireName: String get() = name.lowercase()

    companion object {
        fun parse(value: String): Role = entries.firstOrNull { role -> role.wireName == value.trim().lowercase() }
            ?: throw IllegalArgumentException(
                "Unknown role '$value'. Supported roles: ${entries.joinToString { role -> role.wireName }}"
            )
    }
}

/** Limits for one client. A null field is not limited by the client itself; configured defaults may still apply. */
data class ClientQuota(
    /** Devices the client may hold or wait for at once, across all of its sessions. */
    val maxDevices: Int? = null,
    /** Upper bound for a session's whole life, from creation to expiry. */
    val maxSessionLifetimeSeconds: Long? = null,
    /** Highest scheduling priority the client may ask for. */
    val maxPriority: Int? = null
) {
    init {
        require(maxDevices == null || maxDevices > 0) { "maxDevices must be positive" }
        require(maxSessionLifetimeSeconds == null || maxSessionLifetimeSeconds > 0) { "maxSessionLifetimeSeconds must be positive" }
        require(maxPriority == null || maxPriority >= 0) { "maxPriority must not be negative" }
    }

    /** This quota with every unset field taken from [defaults]. */
    fun orDefaults(defaults: ClientQuota): ClientQuota = ClientQuota(
        maxDevices = maxDevices ?: defaults.maxDevices,
        maxSessionLifetimeSeconds = maxSessionLifetimeSeconds ?: defaults.maxSessionLifetimeSeconds,
        maxPriority = maxPriority ?: defaults.maxPriority
    )

    companion object {
        val UNLIMITED = ClientQuota()
    }
}

/** Who performs an operation. Every API call is made by exactly one authenticated actor. */
data class Actor(
    val id: String,
    val name: String,
    val role: Role,
    /** The limits in force for this actor, configured defaults already applied. */
    val quota: ClientQuota = ClientQuota.UNLIMITED,
    /** Where the request came from, for the audit log. */
    val origin: String? = null
) {
    val isAdmin: Boolean get() = role == Role.ADMIN

    /** Only a session's owner, or an admin, may release it, wait on it or extend it. */
    fun canManage(session: Session): Boolean = isAdmin || (session.ownerId != null && session.ownerId == id)

    companion object {
        /** The manager itself: expiry, cleanup, rollback and tests that bypass the API. */
        val SYSTEM = Actor(id = "system", name = "system", role = Role.ADMIN)
    }
}
