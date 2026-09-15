package dev.shepherd.infra.auth

import dev.shepherd.domain.audit.AuditActions
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.AccessDeniedException
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.model.AuthConfig
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A personal token just issued; [secret] cannot be retrieved again. */
data class IssuedUserToken(val token: UserTokenRecord, val secret: String)

/** A user whose password was just set; [temporaryPassword] is non-null when the manager generated it. */
data class UserWithPassword(val user: UserRecord, val temporaryPassword: String?)

/**
 * People: local accounts, accounts provisioned from LDAP or OIDC, their personal API tokens, and
 * the rule that someone must always be able to administer the manager.
 */
class Accounts(
    private val users: UserStore,
    private val tokens: UserTokenStore,
    private val webSessions: WebSessionStore,
    private val passwords: PasswordHasher = PasswordHasher(),
    private val audit: AuditTrail = AuditTrail.NONE,
    private val authConfig: () -> AuthConfig = { AuthConfig() },
    private val quotaDefaults: () -> ClientQuota = { ClientQuota.UNLIMITED },
    /** Admin API clients and the static admin token, which also keep the manager administrable. */
    private val otherAdmins: suspend () -> Long = { 0L },
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(Accounts::class.java)
    private val lastTouched = ConcurrentHashMap<String, Instant>()

    fun toActor(user: UserRecord, origin: String? = null): Actor =
        Actor(id = user.id, name = user.username, role = user.role, quota = effectiveQuota(user), origin = origin)

    /** Admins are limited only by quotas set on them; everyone else also gets the configured defaults. */
    fun effectiveQuota(user: UserRecord): ClientQuota = if (user.role == Role.ADMIN) user.quota else user.quota.orDefaults(quotaDefaults())

    suspend fun findById(id: String): UserRecord? = users.findById(id)

    suspend fun findByUsername(username: String): UserRecord? = users.findByUsername(username)

    suspend fun getUser(id: String): UserRecord = users.findById(id) ?: throw ResourceNotFoundException("User $id not found")

    suspend fun listUsers(includeDisabled: Boolean = false): List<UserRecord> = users.list(includeDisabled)

    suspend fun countActiveAdmins(): Long = users.countActiveAdmins()

    /**
     * Makes sure someone can sign in and administer the manager. When no active admin user exists,
     * creates `admin` — with [presetPassword] (`MSH_ADMIN_PASSWORD`) when given, otherwise with a
     * generated password that must be changed at first sign-in. The generated password is written to
     * [passwordFile] with owner-only permissions and returned with the account, to be shown once.
     */
    suspend fun bootstrap(passwordFile: File?, presetPassword: String?): UserWithPassword? {
        if (users.countActiveAdmins() > 0) {
            return null
        }
        val username: String = if (users.findByUsername(BOOTSTRAP_USERNAME) == null) {
            BOOTSTRAP_USERNAME
        } else {
            "$BOOTSTRAP_USERNAME-${clock.instant().epochSecond}"
        }
        val preset: String? = presetPassword?.takeIf { value -> value.isNotBlank() }
        preset?.let { value -> PasswordHasher.requireAcceptable(value, username, authConfig().local.minPasswordLength) }
        val password: String = preset ?: PasswordHasher.generate()
        val record = newLocalUser(
            username,
            displayName = "Administrator",
            email = null,
            role = Role.ADMIN,
            createdBy = BOOTSTRAP_CREATED_BY
        )
            .copy(mustChangePassword = preset == null)
        users.insert(record, passwords.hash(password))
        audit.record(
            Actor.SYSTEM,
            AuditActions.USER_CREATE,
            target = username,
            details = mapOf("role" to Role.ADMIN.wireName, "reason" to "first start")
        )
        if (preset != null) {
            return null
        }
        passwordFile?.let { file -> OwnerOnlyFile.write(file, password) }
        return UserWithPassword(record, password)
    }

    /** A local user; without [password] a temporary one is generated. Either way it must be changed at first sign-in. */
    suspend fun createLocalUser(
        actor: Actor,
        username: String,
        password: String?,
        displayName: String?,
        email: String?,
        role: Role,
        quota: ClientQuota
    ): UserWithPassword {
        if (!authConfig().local.enabled) {
            throw ConflictException("Local accounts are turned off (auth.local.enabled); people sign in through a directory")
        }
        val name: String = username.trim()
        requireUsername(name)
        requirePersonRole(role)
        if (users.findByUsername(name) != null) {
            throw ConflictException("A user named '$name' already exists")
        }
        password?.let { value -> PasswordHasher.requireAcceptable(value, name, authConfig().local.minPasswordLength) }
        val temporary: String? = if (password == null) PasswordHasher.generate() else null
        val record = newLocalUser(name, displayName.cleaned(), email.cleaned(), role, createdBy = actor.name).copy(quota = quota)
        users.insert(record, passwords.hash(password ?: checkNotNull(temporary)))
        audit.record(actor, AuditActions.USER_CREATE, target = name, details = mapOf("userId" to record.id, "role" to role.wireName))
        return UserWithPassword(record, temporary)
    }

    /** Absent arguments stay as they are; an empty [displayName] or [email] clears it. */
    suspend fun updateUser(
        actor: Actor,
        id: String,
        displayName: String? = null,
        email: String? = null,
        role: Role? = null,
        quota: ClientQuota? = null,
        active: Boolean? = null
    ): UserRecord {
        val current: UserRecord = getUser(id)
        if (role != null && role != current.role) {
            requirePersonRole(role)
            if (current.roleManagedByProvider) {
                throw ConflictException(
                    "${current.username}'s role comes from ${current.provider} groups; change the group mapping in msh.yaml instead"
                )
            }
            if (current.role == Role.ADMIN && current.isActive) {
                ensureAnotherAdmin(current)
            }
        }
        if (active == false && current.isActive && current.role == Role.ADMIN) {
            ensureAnotherAdmin(current)
        }
        val updated: UserRecord = current.copy(
            displayName = if (displayName != null) displayName.cleaned() else current.displayName,
            email = if (email != null) email.cleaned() else current.email,
            role = role ?: current.role,
            quota = quota ?: current.quota
        )
        if (updated != current) {
            users.update(updated)
            audit.record(
                actor,
                AuditActions.USER_UPDATE,
                target = current.username,
                details = buildMap {
                    if (updated.role != current.role) put("role", updated.role.wireName)
                    if (updated.quota != current.quota) put("quota", updated.quota.toString())
                }
            )
        }
        if (active != null && active != current.isActive) {
            users.setDisabled(id, if (active) null else clock.instant())
            if (!active) {
                webSessions.deleteForUser(id)
            }
            audit.record(actor, if (active) AuditActions.USER_ENABLE else AuditActions.USER_DISABLE, target = current.username)
        }
        return getUser(id)
    }

    /** Sets a new password that must be changed at the next sign-in, and signs the user out everywhere. */
    suspend fun resetPassword(actor: Actor, id: String, password: String?): UserWithPassword {
        val user: UserRecord = getUser(id)
        if (user.source != UserSource.LOCAL) {
            throw ConflictException("${user.username} signs in through ${user.provider}; reset the password there")
        }
        password?.let { value -> PasswordHasher.requireAcceptable(value, user.username, authConfig().local.minPasswordLength) }
        val temporary: String? = if (password == null) PasswordHasher.generate() else null
        users.setPassword(id, passwords.hash(password ?: checkNotNull(temporary)), mustChange = true)
        webSessions.deleteForUser(id)
        audit.record(actor, AuditActions.USER_PASSWORD_RESET, target = user.username)
        return UserWithPassword(getUser(id), temporary)
    }

    /** The user when [password] is theirs; null otherwise, after spending the same effort either way. */
    suspend fun verifyLocalPassword(user: UserRecord, password: String): UserRecord? {
        val hash: String? = if (user.source == UserSource.LOCAL && user.isActive) users.passwordHash(user.id) else null
        if (hash == null) {
            passwords.verifyDecoy(password)
            return null
        }
        if (!passwords.verify(password, hash)) {
            return null
        }
        if (passwords.needsRehash(hash)) {
            users.setPassword(user.id, passwords.hash(password), user.mustChangePassword)
        }
        return user
    }

    /** For usernames that match no account: takes as long as a real check. */
    fun spendDecoyCheck(password: String) = passwords.verifyDecoy(password)

    /** Changes a local user's own password and signs them out of every other browser session. */
    suspend fun changePassword(user: UserRecord, currentPassword: String, newPassword: String, keepSessionIdHash: String? = null) {
        if (user.source != UserSource.LOCAL) {
            throw ConflictException("${user.username} signs in through ${user.provider}; change the password there")
        }
        val hash: String = users.passwordHash(user.id) ?: throw ConflictException("${user.username} has no password to change")
        if (!passwords.verify(currentPassword, hash)) {
            throw AccessDeniedException("The current password is not correct")
        }
        require(currentPassword != newPassword) { "The new password must differ from the current one" }
        PasswordHasher.requireAcceptable(newPassword, user.username, authConfig().local.minPasswordLength)
        users.setPassword(user.id, passwords.hash(newPassword), mustChange = false)
        webSessions.deleteForUser(user.id, keepIdHash = keepSessionIdHash)
        audit.record(toActor(user), AuditActions.USER_PASSWORD_CHANGE, target = user.username)
    }

    /** True while the account the manager made at first start is still nobody's: no name, no password of their own. */
    fun isUnclaimed(user: UserRecord): Boolean =
        user.mustChangePassword && user.source == UserSource.LOCAL && user.createdBy == BOOTSTRAP_CREATED_BY

    /**
     * Finishes a first sign-in: the person replaces the password they were given and, on the
     * account the manager created at first start, takes it over under a name of their own.
     *
     * This session was opened with the very password being replaced, so asking for it again would
     * prove nothing; [changePassword] guards every later change with it.
     *
     * @param username a new name for an unclaimed account; null or unchanged leaves it alone.
     */
    suspend fun setUpAccount(
        user: UserRecord,
        username: String?,
        displayName: String?,
        newPassword: String,
        keepSessionIdHash: String? = null
    ): UserRecord {
        if (!user.mustChangePassword) {
            throw ConflictException("${user.username} already chose a password; change it with the current one")
        }
        if (user.source != UserSource.LOCAL) {
            throw ConflictException("${user.username} signs in through ${user.provider}; change the password there")
        }
        val wanted: String? = username.cleaned()?.takeIf { name -> name != user.username }
        if (wanted != null) {
            if (!isUnclaimed(user)) {
                throw ConflictException("${user.username} cannot be renamed here; an admin renames people")
            }
            requireUsername(wanted)
            if (users.findByUsername(wanted) != null) {
                throw ConflictException("A user named '$wanted' already exists")
            }
        }
        val name: String = wanted ?: user.username
        PasswordHasher.requireAcceptable(newPassword, name, authConfig().local.minPasswordLength)
        val hash: String = users.passwordHash(user.id) ?: throw ConflictException("${user.username} has no password to change")
        require(!passwords.verify(newPassword, hash)) { "The new password must differ from the one you were given" }

        val claimed: UserRecord = user.copy(username = name, displayName = displayName.cleaned() ?: user.displayName)
        if (wanted != null) {
            users.rename(user.id, name)
        }
        if (claimed.displayName != user.displayName) {
            users.update(claimed)
        }
        users.setPassword(user.id, passwords.hash(newPassword), mustChange = false)
        webSessions.deleteForUser(user.id, keepIdHash = keepSessionIdHash)
        if (claimed != user) {
            audit.record(
                toActor(claimed),
                AuditActions.USER_UPDATE,
                target = name,
                details = buildMap {
                    if (wanted != null) put("renamedFrom", user.username)
                    if (claimed.displayName != user.displayName) put("displayName", claimed.displayName ?: "")
                }
            )
        }
        audit.record(toActor(claimed), AuditActions.USER_PASSWORD_CHANGE, target = name)
        return getUser(user.id)
    }

    /**
     * The account of someone a directory vouched for, created at their first sign-in. Its role comes
     * from the directory's groups whenever a role mapping is configured.
     *
     * @throws AccessDeniedException when no mapped group and no default role apply, or the account is disabled.
     */
    suspend fun provisionExternal(identity: ExternalIdentity): UserRecord {
        val role: Role = RoleMapping.resolve(
            groups = identity.groups,
            mapping = identity.roleMapping,
            defaultRole = identity.defaultRole,
            ignoreCase = identity.source == UserSource.LDAP
        ) ?: throw AccessDeniedException("${identity.username} is not in any group that may use Marathon Shepherd")
        val managed: Boolean = identity.roleMapping.isNotEmpty()
        val existing: UserRecord? = users.findByExternalIdentity(identity.provider, identity.externalId)
        if (existing != null) {
            if (!existing.isActive) {
                throw AccessDeniedException("The account ${existing.username} is disabled")
            }
            val refreshed: UserRecord = existing.copy(
                displayName = identity.displayName ?: existing.displayName,
                email = identity.email ?: existing.email,
                role = if (managed) role else existing.role,
                roleManagedByProvider = managed
            )
            if (refreshed != existing) {
                users.update(refreshed)
                if (refreshed.role != existing.role) {
                    audit.record(
                        Actor.SYSTEM,
                        AuditActions.USER_UPDATE,
                        target = existing.username,
                        details = mapOf("role" to refreshed.role.wireName, "reason" to "${identity.provider} groups")
                    )
                }
            }
            return refreshed
        }
        val record = UserRecord(
            id = RandomTokens.id(USER_ID_PREFIX),
            username = availableUsername(identity),
            displayName = identity.displayName,
            email = identity.email,
            source = identity.source,
            provider = identity.provider,
            externalId = identity.externalId,
            role = role,
            roleManagedByProvider = managed,
            quota = ClientQuota.UNLIMITED,
            mustChangePassword = false,
            createdAt = clock.instant(),
            createdBy = identity.provider,
            lastLoginAt = null,
            disabledAt = null
        )
        users.insert(record, passwordHash = null)
        audit.record(
            Actor.SYSTEM,
            AuditActions.USER_CREATE,
            target = record.username,
            details = mapOf(
                "userId" to record.id,
                "source" to identity.source.wireName,
                "provider" to identity.provider,
                "role" to role.wireName
            )
        )
        return record
    }

    suspend fun recordLogin(user: UserRecord) = users.recordLogin(user.id, clock.instant())

    suspend fun listTokens(userId: String, includeRevoked: Boolean = false): List<UserTokenRecord> = tokens.listForUser(
        userId,
        includeRevoked
    )

    suspend fun issueToken(actor: Actor, user: UserRecord, name: String, expiresInDays: Int?): IssuedUserToken {
        val label: String = name.trim()
        require(label.length in 1..TOKEN_NAME_LIMIT) { "Token names are 1-$TOKEN_NAME_LIMIT characters" }
        require(expiresInDays == null || expiresInDays in 1..MAX_TOKEN_DAYS) { "expiresInDays must be between 1 and $MAX_TOKEN_DAYS" }
        if (tokens.listForUser(user.id, includeRevoked = false).size >= MAX_TOKENS_PER_USER) {
            throw ConflictException("${user.username} already has $MAX_TOKENS_PER_USER tokens; revoke one first")
        }
        val secret: String = ApiKeys.generate()
        val now: Instant = clock.instant()
        val record = UserTokenRecord(
            id = RandomTokens.id(TOKEN_ID_PREFIX),
            userId = user.id,
            name = label,
            prefix = ApiKeys.displayPrefix(secret),
            createdAt = now,
            lastUsedAt = null,
            expiresAt = expiresInDays?.let { days -> now.plus(Duration.ofDays(days.toLong())) },
            revokedAt = null
        )
        tokens.insert(record, ApiKeys.hash(secret))
        audit.record(actor, AuditActions.TOKEN_CREATE, target = user.username, details = mapOf("tokenId" to record.id, "name" to label))
        return IssuedUserToken(record, secret)
    }

    suspend fun revokeToken(actor: Actor, userId: String, tokenId: String): UserTokenRecord {
        val record: UserTokenRecord = tokens.findById(tokenId)?.takeIf { token -> token.userId == userId }
            ?: throw ResourceNotFoundException("Token $tokenId not found")
        val user: UserRecord = getUser(userId)
        if (record.revokedAt == null) {
            tokens.revoke(tokenId, clock.instant())
            audit.record(actor, AuditActions.TOKEN_REVOKE, target = user.username, details = mapOf("tokenId" to tokenId))
        }
        return tokens.findById(tokenId) ?: record
    }

    /** The person behind a personal token, or null when it is unknown, revoked, expired or their account is not usable. */
    suspend fun authenticateToken(token: String): Actor? {
        val record: UserTokenRecord = tokens.findByHash(ApiKeys.hash(token.trim())) ?: return null
        val now: Instant = clock.instant()
        if (!record.isUsableAt(now)) {
            return null
        }
        val user: UserRecord = users.findById(record.userId)?.takeIf { account -> account.isActive && !account.mustChangePassword }
            ?: return null
        touch(record.id, now)
        return toActor(user)
    }

    private suspend fun ensureAnotherAdmin(target: UserRecord) {
        if (users.countActiveAdmins() - 1 + otherAdmins() <= 0) {
            throw ConflictException("'${target.username}' is the last active admin; make someone else an admin first")
        }
    }

    private suspend fun availableUsername(identity: ExternalIdentity): String {
        val base: String = identity.username.trim()
            .replace(Regex("[^A-Za-z0-9._@+-]"), "_")
            .trimStart('.', '_', '@', '+', '-')
            .take(USERNAME_LIMIT - PROVIDER_SUFFIX_ROOM)
            .ifEmpty { identity.provider }
        val candidates: Sequence<String> = sequenceOf(base, "$base@${identity.provider}") +
            generateSequence(2) { index -> index + 1 }.map { index -> "$base@${identity.provider}-$index" }
        return candidates.first { candidate -> users.findByUsername(candidate) == null }
    }

    private fun newLocalUser(username: String, displayName: String?, email: String?, role: Role, createdBy: String): UserRecord =
        UserRecord(
            id = RandomTokens.id(USER_ID_PREFIX),
            username = username,
            displayName = displayName,
            email = email,
            source = UserSource.LOCAL,
            provider = null,
            externalId = null,
            role = role,
            roleManagedByProvider = false,
            quota = ClientQuota.UNLIMITED,
            mustChangePassword = true,
            createdAt = clock.instant(),
            createdBy = createdBy,
            lastLoginAt = null,
            disabledAt = null
        )

    private fun requireUsername(name: String) = require(USERNAME.matches(name)) {
        "Usernames are 1-128 letters, digits, '.', '_', '@', '+' or '-', starting with a letter or digit"
    }

    private fun requirePersonRole(role: Role) {
        require(role != Role.PROVIDER) { "The provider role is for adapters; create an API client for them instead" }
    }

    /** Records last use at most once per interval, so authentication does not write on every request. */
    private suspend fun touch(tokenId: String, now: Instant) {
        val previous: Instant? = lastTouched[tokenId]
        if (previous != null && Duration.between(previous, now) < TOUCH_INTERVAL) {
            return
        }
        lastTouched[tokenId] = now
        try {
            tokens.touch(tokenId, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.debug("Could not record last use of token {}: {}", tokenId, error.message)
        }
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { value -> value.isNotEmpty() }

    companion object {
        const val BOOTSTRAP_USERNAME: String = "admin"

        /** Recorded as the creator of the first-start account, which marks it as nobody's yet. */
        const val BOOTSTRAP_CREATED_BY: String = "first start"
        const val USER_ID_PREFIX: String = "usr_"
        const val TOKEN_ID_PREFIX: String = "tok_"
        private const val USERNAME_LIMIT = 128
        private const val PROVIDER_SUFFIX_ROOM = 40
        private const val TOKEN_NAME_LIMIT = 100
        private const val MAX_TOKEN_DAYS = 3650
        private const val MAX_TOKENS_PER_USER = 50
        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)
        private val USERNAME = Regex("^[A-Za-z0-9][A-Za-z0-9._@+-]{0,127}$")
    }
}
