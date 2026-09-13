package dev.shepherd.infra.auth

import dev.shepherd.domain.audit.AuditActions
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.domain.errors.ResourceNotFoundException
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A freshly issued key; [apiKey] is never retrievable again. */
data class IssuedKey(val client: ClientRecord, val apiKey: String)

/**
 * Who may call the API: API-key authentication and client management.
 *
 * Two kinds of credential exist. Client keys live in the database, hashed, and are managed
 * through the admin API. The optional static admin token ([staticAdminToken], from
 * `MSH_ADMIN_TOKEN`) is never stored; it exists so automated deployments can provision
 * a known admin credential without scraping logs.
 */
class AccessControl(
    private val clients: ClientStore,
    private val audit: AuditTrail = AuditTrail.NONE,
    private val quotaDefaults: () -> ClientQuota = { ClientQuota.UNLIMITED },
    staticAdminToken: String? = null,
    /** Active admin users, who also keep the manager administrable. */
    private val otherAdmins: suspend () -> Long = { 0L },
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(AccessControl::class.java)
    private val staticAdminToken: String? = staticAdminToken?.trim()?.takeIf { token -> token.isNotEmpty() }
    private val lastTouched = ConcurrentHashMap<String, Instant>()

    val hasStaticAdmin: Boolean get() = staticAdminToken != null

    /** The actor behind [token], or null when the token is unknown or revoked. */
    suspend fun authenticate(token: String): Actor? {
        val presented: String = token.trim()
        if (presented.isEmpty()) {
            return null
        }
        staticAdminToken?.let { expected ->
            if (ApiKeys.constantTimeEquals(presented, expected)) return STATIC_ADMIN
        }
        val record: ClientRecord = clients.findByKeyHash(ApiKeys.hash(presented)) ?: return null
        if (!record.isActive) {
            return null
        }
        touch(record.id)
        return toActor(record)
    }

    fun toActor(record: ClientRecord): Actor = Actor(id = record.id, name = record.name, role = record.role, quota = effectiveQuota(record))

    /** Admins are limited only by quotas set on them explicitly; everyone else also gets the configured defaults. */
    fun effectiveQuota(record: ClientRecord): ClientQuota =
        if (record.role == Role.ADMIN) record.quota else record.quota.orDefaults(quotaDefaults())

    suspend fun createClient(actor: Actor, name: String, role: Role, description: String?, quota: ClientQuota): IssuedKey {
        val normalizedName: String = name.trim()
        require(NAME_PATTERN.matches(normalizedName)) {
            "Client name must be 1-64 characters of letters, digits, '.', '_' or '-', starting with a letter or digit"
        }
        if (clients.findActiveByName(normalizedName) != null) {
            throw ConflictException("A client named '$normalizedName' already exists")
        }
        val key: String = ApiKeys.generate()
        val record = ClientRecord(
            id = "cli_" + UUID.randomUUID().toString().replace("-", "").take(CLIENT_ID_LENGTH),
            name = normalizedName,
            role = role,
            keyPrefix = ApiKeys.displayPrefix(key),
            description = description?.trim()?.takeIf { text -> text.isNotEmpty() },
            quota = quota,
            createdAt = clock.instant(),
            createdBy = actor.name,
            lastUsedAt = null,
            revokedAt = null
        )
        clients.insert(record, ApiKeys.hash(key))
        audit.record(
            actor,
            AuditActions.CLIENT_CREATE,
            target = normalizedName,
            details = mapOf("clientId" to record.id, "role" to role.wireName)
        )
        return IssuedKey(record, key)
    }

    suspend fun listClients(includeRevoked: Boolean = false): List<ClientRecord> = clients.list(includeRevoked)

    suspend fun getClient(id: String): ClientRecord = clients.findById(id) ?: throw ResourceNotFoundException("Client $id not found")

    suspend fun updateClient(actor: Actor, id: String, role: Role?, description: String?, quota: ClientQuota?): ClientRecord {
        val current: ClientRecord = requireActive(id)
        if (role != null && current.role == Role.ADMIN && role != Role.ADMIN) {
            ensureAnotherAdmin(current)
        }
        val updated: ClientRecord = current.copy(
            role = role ?: current.role,
            description = description ?: current.description,
            quota = quota ?: current.quota
        )
        clients.update(updated)
        audit.record(
            actor,
            AuditActions.CLIENT_UPDATE,
            target = current.name,
            details = buildMap {
                role?.let { newRole -> put("role", newRole.wireName) }
                quota?.let { newQuota -> put("quota", newQuota.describe()) }
            }
        )
        return updated
    }

    suspend fun rotateKey(actor: Actor, id: String): IssuedKey {
        val current: ClientRecord = requireActive(id)
        val key: String = ApiKeys.generate()
        val updated: ClientRecord = current.copy(keyPrefix = ApiKeys.displayPrefix(key))
        clients.replaceKey(id, updated.keyPrefix, ApiKeys.hash(key))
        audit.record(actor, AuditActions.CLIENT_ROTATE, target = current.name)
        return IssuedKey(updated, key)
    }

    suspend fun revokeClient(actor: Actor, id: String): ClientRecord {
        val current: ClientRecord = requireActive(id)
        if (current.role == Role.ADMIN) {
            ensureAnotherAdmin(current)
        }
        val revokedAt: Instant = clock.instant()
        clients.revoke(id, revokedAt)
        lastTouched.remove(id)
        audit.record(actor, AuditActions.CLIENT_REVOKE, target = current.name)
        return current.copy(revokedAt = revokedAt)
    }

    private suspend fun requireActive(id: String): ClientRecord {
        val record: ClientRecord = getClient(id)
        if (!record.isActive) {
            throw ConflictException("Client ${record.name} is revoked")
        }
        return record
    }

    /** Refuses to remove the last way to administer the manager. */
    private suspend fun ensureAnotherAdmin(target: ClientRecord) {
        if (staticAdminToken != null) {
            return
        }
        if (clients.countActiveAdmins() - 1 + otherAdmins() <= 0) {
            throw ConflictException(
                "'${target.name}' is the last active admin; make another client or user an admin, or set MSH_ADMIN_TOKEN first"
            )
        }
    }

    /** Records last use at most once per interval, so authentication does not write on every request. */
    private suspend fun touch(clientId: String) {
        val now: Instant = clock.instant()
        val previous: Instant? = lastTouched[clientId]
        if (previous != null && Duration.between(previous, now) < TOUCH_INTERVAL) {
            return
        }
        lastTouched[clientId] = now
        try {
            clients.touch(clientId, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.debug("Could not record last use of client {}: {}", clientId, error.message)
        }
    }

    private fun ClientQuota.describe(): String =
        "maxDevices=${maxDevices ?: "-"}, maxSessionLifetimeSeconds=${maxSessionLifetimeSeconds ?: "-"}, maxPriority=${maxPriority ?: "-"}"

    companion object {
        private const val CLIENT_ID_LENGTH: Int = 12
        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)
        private val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        /** The actor behind `MSH_ADMIN_TOKEN`. */
        val STATIC_ADMIN = Actor(id = "static-admin", name = "admin-token", role = Role.ADMIN)
    }
}
