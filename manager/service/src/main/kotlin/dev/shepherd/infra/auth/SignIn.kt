package dev.shepherd.infra.auth

import dev.shepherd.domain.audit.AuditActions
import dev.shepherd.domain.audit.AuditOutcome
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.AccessDeniedException
import dev.shepherd.domain.model.AuthConfig
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A browser session just started; [cookieValue] goes into the cookie and is not stored anywhere. */
data class StartedWebSession(val cookieValue: String, val session: WebSessionRecord, val user: UserRecord)

data class ResolvedWebSession(val user: UserRecord, val session: WebSessionRecord)

/**
 * Signing people in: passwords against local accounts or LDAP, identities from OIDC providers, and
 * the browser sessions that follow. Every attempt, successful or not, goes into the audit log.
 */
class SignIn(
    private val accounts: Accounts,
    private val webSessions: WebSessionStore,
    private val ldap: LdapSignIn,
    private val oidc: OidcSignIn,
    private val throttle: LoginThrottle,
    private val authConfig: () -> AuthConfig,
    private val audit: AuditTrail = AuditTrail.NONE,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(SignIn::class.java)

    /**
     * The account whose password this is: a local account, else — for usernames that are not local —
     * an LDAP account, created at its first sign-in.
     *
     * @throws SignInFailure with the same message for an unknown username and a wrong password.
     */
    suspend fun withPassword(username: String, password: String, address: String?, method: String = METHOD_PASSWORD): UserRecord {
        val name: String = username.trim()
        if (name.isEmpty() || password.isEmpty() || name.length > USERNAME_LIMIT) {
            throw SignInFailure(WRONG_CREDENTIALS)
        }
        throttle.lockedUntil(name, address)?.let { until ->
            audit.record(
                anonymous(address),
                AuditActions.AUTH_LOGIN,
                target = name,
                outcome = AuditOutcome.DENIED,
                details = mapOf("reason" to "locked out")
            )
            throw SignInFailure(
                "Too many failed sign-ins; try again later",
                retryAfterSeconds = Duration.between(clock.instant(), until).seconds + 1
            )
        }
        val user: UserRecord? = try {
            checkPassword(name, password)
        } catch (denied: AccessDeniedException) {
            audit.record(
                anonymous(address),
                AuditActions.AUTH_LOGIN,
                target = name,
                outcome = AuditOutcome.DENIED,
                details = mapOf("reason" to denied.message.orEmpty())
            )
            throw SignInFailure(denied.message ?: WRONG_CREDENTIALS)
        } catch (unavailable: DirectoryUnavailableException) {
            logger.warn("LDAP sign-in failed: {}", unavailable.message)
            throw SignInFailure("The directory cannot be reached right now; try again later")
        }
        if (user == null) {
            throttle.recordFailure(name, address)
            audit.record(
                anonymous(address),
                AuditActions.AUTH_LOGIN,
                target = name,
                outcome = AuditOutcome.DENIED,
                details = mapOf("method" to method)
            )
            throw SignInFailure(WRONG_CREDENTIALS)
        }
        throttle.recordSuccess(name)
        return user
    }

    /** Changes a password for someone without a session — a temporary password from the CLI, say. */
    suspend fun changePassword(username: String, currentPassword: String, newPassword: String, address: String?) {
        val user: UserRecord = withPassword(username, currentPassword, address, method = "password change")
        accounts.changePassword(user, currentPassword, newPassword)
    }

    /** The account of someone an OIDC provider just signed in, created at their first sign-in. */
    suspend fun withOidc(providerId: String, code: String?, state: String?, error: String?, address: String?): Pair<UserRecord, String> {
        val (identity, returnTo) = oidc.complete(providerId, code, state, error)
        val user: UserRecord = try {
            accounts.provisionExternal(identity)
        } catch (denied: AccessDeniedException) {
            audit.record(
                anonymous(address),
                AuditActions.AUTH_LOGIN,
                target = identity.username,
                outcome = AuditOutcome.DENIED,
                details = mapOf("method" to "oidc:$providerId", "reason" to denied.message.orEmpty())
            )
            throw SignInFailure(denied.message ?: "You may not use Marathon Shepherd")
        }
        return user to returnTo
    }

    suspend fun startOidc(providerId: String, returnTo: String?): String = oidc.start(providerId, returnTo)

    suspend fun startSession(user: UserRecord, address: String?, userAgent: String?, method: String): StartedWebSession {
        val cookieValue: String = RandomTokens.urlSafe(SESSION_BYTES)
        val now: Instant = clock.instant()
        val record = WebSessionRecord(
            idHash = ApiKeys.hash(cookieValue),
            userId = user.id,
            csrfToken = RandomTokens.urlSafe(SESSION_BYTES),
            createdAt = now,
            lastSeenAt = now,
            expiresAt = now.plus(Duration.ofHours(authConfig().sessions.maxLifetimeHours)),
            origin = address,
            userAgent = userAgent
        )
        webSessions.insert(record)
        accounts.recordLogin(user)
        audit.record(accounts.toActor(user, address), AuditActions.AUTH_LOGIN, target = user.username, details = mapOf("method" to method))
        return StartedWebSession(cookieValue, record, user)
    }

    /** The session behind a cookie, or null when it is unknown, over or its account is disabled. */
    suspend fun resolveSession(cookieValue: String): ResolvedWebSession? {
        if (cookieValue.length !in COOKIE_LENGTHS) {
            return null
        }
        val record: WebSessionRecord = webSessions.find(ApiKeys.hash(cookieValue)) ?: return null
        val now: Instant = clock.instant()
        val idleLimit: Duration = Duration.ofMinutes(authConfig().sessions.idleTimeoutMinutes)
        if (!record.expiresAt.isAfter(now) || record.lastSeenAt.plus(idleLimit).isBefore(now)) {
            webSessions.delete(record.idHash)
            return null
        }
        val user: UserRecord = accounts.findById(record.userId)?.takeIf { account -> account.isActive } ?: run {
            webSessions.delete(record.idHash)
            return null
        }
        if (Duration.between(record.lastSeenAt, now) >= TOUCH_INTERVAL) {
            webSessions.touch(record.idHash, now)
        }
        return ResolvedWebSession(user, record)
    }

    suspend fun endSession(session: ResolvedWebSession, address: String?) {
        webSessions.delete(session.session.idHash)
        audit.record(accounts.toActor(session.user, address), AuditActions.AUTH_LOGOUT, target = session.user.username)
    }

    /** Deletes sessions that ended; run periodically. */
    suspend fun purgeEndedSessions(): Int {
        val now: Instant = clock.instant()
        return webSessions.deleteEnded(now, idleBefore = now.minus(Duration.ofMinutes(authConfig().sessions.idleTimeoutMinutes)))
    }

    private suspend fun checkPassword(username: String, password: String): UserRecord? {
        val config: AuthConfig = authConfig()
        val existing: UserRecord? = accounts.findByUsername(username)
        return when {
            existing?.source == UserSource.LOCAL -> if (config.local.enabled) {
                accounts.verifyLocalPassword(
                    existing,
                    password
                )
            } else {
                decoy(password)
            }
            // People from an OIDC provider have no password here.
            existing?.source == UserSource.OIDC -> decoy(password)
            config.ldap != null -> ldap.authenticate(
                config.ldap,
                username,
                password
            )?.let { identity -> accounts.provisionExternal(identity) }
            else -> decoy(password)
        }
    }

    private fun decoy(password: String): UserRecord? {
        accounts.spendDecoyCheck(password)
        return null
    }

    private fun anonymous(address: String?): Actor = Actor(id = "anonymous", name = "anonymous", role = Role.VIEWER, origin = address)

    companion object {
        const val METHOD_PASSWORD: String = "password"
        const val WRONG_CREDENTIALS: String = "Wrong username or password"
        private const val USERNAME_LIMIT = 256
        private const val SESSION_BYTES = 32
        private val COOKIE_LENGTHS: IntRange = 40..64
        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
