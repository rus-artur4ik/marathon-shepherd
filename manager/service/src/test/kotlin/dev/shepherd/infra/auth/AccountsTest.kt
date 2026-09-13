package dev.shepherd.infra.auth

import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.AccessDeniedException
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.domain.model.AuthConfig
import dev.shepherd.domain.provider.SettableClock
import dev.shepherd.infra.db.ShepherdDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `first start creates an admin with a one-time password written owner-only`() = runTest {
        val accounts = accounts("bootstrap")
        val file = File(tempDir, "initial-admin-password")

        val created: UserWithPassword = assertNotNull(accounts.bootstrap(file, presetPassword = null))
        val again: UserWithPassword? = accounts.bootstrap(file, presetPassword = null)

        assertEquals("admin", created.user.username)
        assertEquals(Role.ADMIN, created.user.role)
        assertTrue(created.user.mustChangePassword)
        assertEquals(created.temporaryPassword, file.readText().trim())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
        assertNotNull(accounts.verifyLocalPassword(created.user, checkNotNull(created.temporaryPassword)))
        assertNull(again, "an existing admin must not trigger another account")
    }

    @Test
    fun `MSH_ADMIN_PASSWORD sets the first password without forcing a change`() = runTest {
        val accounts = accounts("preset")
        val file = File(tempDir, "unused")

        val announced: UserWithPassword? = accounts.bootstrap(file, presetPassword = "operator chosen secret")
        val admin: UserRecord = checkNotNull(accounts.findByUsername("admin"))

        assertNull(announced)
        assertFalse(file.exists())
        assertFalse(admin.mustChangePassword)
        assertNotNull(accounts.verifyLocalPassword(admin, "operator chosen secret"))
    }

    @Test
    fun `the last admin can be neither disabled nor demoted`() = runTest {
        val accounts = accounts("last-admin")
        val admin: UserRecord = checkNotNull(accounts.bootstrap(null, null)).user

        assertFailsWith<ConflictException> { accounts.updateUser(Actor.SYSTEM, admin.id, active = false) }
        assertFailsWith<ConflictException> { accounts.updateUser(Actor.SYSTEM, admin.id, role = Role.USER) }
        accounts.createLocalUser(Actor.SYSTEM, "ops", null, null, null, Role.ADMIN, ClientQuota.UNLIMITED)
        accounts.updateUser(Actor.SYSTEM, admin.id, active = false)

        assertFalse(accounts.getUser(admin.id).isActive)
        assertFailsWith<IllegalArgumentException> {
            accounts.createLocalUser(Actor.SYSTEM, "adapter", null, null, null, Role.PROVIDER, ClientQuota.UNLIMITED)
        }
    }

    @Test
    fun `personal tokens work until revoked or expired, and stop when their person is disabled`() = runTest {
        val clock = SettableClock(Instant.parse("2026-09-13T10:00:00Z"))
        val accounts = accounts("tokens", clock = clock)
        val temporary: String = checkNotNull(
            accounts.createLocalUser(Actor.SYSTEM, "bob", null, null, null, Role.USER, ClientQuota.UNLIMITED).temporaryPassword
        )
        accounts.changePassword(checkNotNull(accounts.findByUsername("bob")), temporary, "bob's long password")
        val bob: UserRecord = checkNotNull(accounts.findByUsername("bob"))

        val lasting: IssuedUserToken = accounts.issueToken(Actor.SYSTEM, bob, "laptop", expiresInDays = null)
        val shortLived: IssuedUserToken = accounts.issueToken(Actor.SYSTEM, bob, "ci", expiresInDays = 1)
        val signedIn: Actor? = accounts.authenticateToken(lasting.secret)
        clock.now = clock.now.plus(Duration.ofDays(2))
        val expired: Actor? = accounts.authenticateToken(shortLived.secret)
        accounts.revokeToken(Actor.SYSTEM, bob.id, lasting.token.id)
        val revoked: Actor? = accounts.authenticateToken(lasting.secret)
        val another: IssuedUserToken = accounts.issueToken(Actor.SYSTEM, bob, "desktop", expiresInDays = null)
        accounts.updateUser(Actor.SYSTEM, bob.id, active = false)

        assertEquals("bob", signedIn?.name)
        assertEquals(Role.USER, signedIn?.role)
        assertNull(expired)
        assertNull(revoked)
        assertNull(accounts.authenticateToken(another.secret))
    }

    @Test
    fun `directory accounts are created once, follow their groups and never take a local name`() = runTest {
        val accounts = accounts("external")
        accounts.createLocalUser(Actor.SYSTEM, "alice", null, null, null, Role.USER, ClientQuota.UNLIMITED)
        val identity = ExternalIdentity(
            source = UserSource.OIDC,
            provider = "keycloak",
            externalId = "subject-1",
            username = "alice",
            displayName = "Alice",
            email = "alice@example.com",
            groups = setOf("qa"),
            roleMapping = mapOf("qa" to "user", "shepherd-admins" to "admin"),
            defaultRole = null
        )

        val first: UserRecord = accounts.provisionExternal(identity)
        val promoted: UserRecord = accounts.provisionExternal(identity.copy(groups = setOf("shepherd-admins")))

        assertEquals("alice@keycloak", first.username)
        assertEquals(Role.USER, first.role)
        assertTrue(first.roleManagedByProvider)
        assertEquals(first.id, promoted.id)
        assertEquals(Role.ADMIN, promoted.role)
        assertFailsWith<AccessDeniedException> {
            accounts.provisionExternal(
                identity.copy(externalId = "subject-2", groups = setOf("sales"))
            )
        }
        assertFailsWith<ConflictException> { accounts.updateUser(Actor.SYSTEM, promoted.id, role = Role.VIEWER) }
    }

    private fun accounts(name: String, clock: Clock = Clock.systemUTC()): Accounts {
        val database = ShepherdDatabase.sqlite(File(tempDir, "$name.db").absolutePath)
        return Accounts(
            users = UserStore(database),
            tokens = UserTokenStore(database),
            webSessions = WebSessionStore(database),
            passwords = PasswordHasher(iterations = 1_000),
            authConfig = { AuthConfig() },
            clock = clock
        )
    }
}
