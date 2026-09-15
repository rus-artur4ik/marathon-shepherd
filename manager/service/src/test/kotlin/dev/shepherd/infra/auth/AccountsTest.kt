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
    fun `the first administrator takes the account over under a name of their own`() = runTest {
        val accounts = accounts("claim")
        val created: UserWithPassword = assertNotNull(accounts.bootstrap(File(tempDir, "claim-password"), presetPassword = null))
        val temporary: String = checkNotNull(created.temporaryPassword)

        assertTrue(accounts.isUnclaimed(created.user))
        val claimed: UserRecord = accounts.setUpAccount(created.user, "artur", "Artur G.", CHOSEN_PASSWORD)

        assertEquals("artur", claimed.username)
        assertEquals("Artur G.", claimed.displayName)
        assertFalse(claimed.mustChangePassword)
        assertFalse(accounts.isUnclaimed(claimed))
        assertNull(accounts.findByUsername("admin"), "the name it was created with is free again")
        assertNotNull(accounts.verifyLocalPassword(claimed, CHOSEN_PASSWORD))
        assertNull(accounts.verifyLocalPassword(claimed, temporary), "the one-time password is spent")
    }

    @Test
    fun `a first sign-in refuses the password it was given and a name someone else has`() = runTest {
        val accounts = accounts("claim-guards")
        accounts.createLocalUser(Actor.SYSTEM, "taken", "another long passphrase", null, null, Role.USER, ClientQuota.UNLIMITED)
        val created: UserWithPassword = assertNotNull(accounts.bootstrap(File(tempDir, "guard-password"), presetPassword = null))
        val temporary: String = checkNotNull(created.temporaryPassword)

        assertFailsWith<IllegalArgumentException> { accounts.setUpAccount(created.user, "artur", null, temporary) }
        assertFailsWith<ConflictException> { accounts.setUpAccount(created.user, "taken", null, CHOSEN_PASSWORD) }

        val untouched: UserRecord = assertNotNull(accounts.findByUsername("admin"), "a refused form changes nothing")
        assertTrue(untouched.mustChangePassword)
        assertNotNull(accounts.verifyLocalPassword(untouched, temporary))
    }

    @Test
    fun `someone whose password was reset chooses a new one but keeps their name`() = runTest {
        val accounts = accounts("reset-claim")
        val created: UserWithPassword =
            accounts.createLocalUser(Actor.SYSTEM, "gina", null, null, null, Role.USER, ClientQuota.UNLIMITED)

        assertFailsWith<ConflictException> { accounts.setUpAccount(created.user, "gina-renamed", null, CHOSEN_PASSWORD) }
        val settled: UserRecord = accounts.setUpAccount(created.user, null, null, CHOSEN_PASSWORD)

        assertEquals("gina", settled.username)
        assertFalse(settled.mustChangePassword)
        assertNotNull(accounts.verifyLocalPassword(settled, CHOSEN_PASSWORD))
        assertFailsWith<ConflictException>("a settled account changes its password with the current one") {
            accounts.setUpAccount(settled, null, null, "yet another long passphrase")
        }
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
    fun `an admin renames a local account but not one a directory named`() = runTest {
        val accounts = accounts("rename")
        val dana: UserRecord =
            accounts.createLocalUser(Actor.SYSTEM, "dana", null, null, null, Role.USER, ClientQuota.UNLIMITED).user
        accounts.createLocalUser(Actor.SYSTEM, "taken", null, null, null, Role.USER, ClientQuota.UNLIMITED)
        val external: UserRecord = accounts.provisionExternal(
            ExternalIdentity(
                source = UserSource.OIDC,
                provider = "keycloak",
                externalId = "subject-9",
                username = "erin",
                displayName = "Erin",
                email = null,
                groups = setOf("qa"),
                roleMapping = mapOf("qa" to "user"),
                defaultRole = null
            )
        )

        val renamed: UserRecord = accounts.updateUser(Actor.SYSTEM, dana.id, username = "dana.scully")

        assertEquals("dana.scully", renamed.username)
        assertEquals(dana.id, renamed.id, "the same account, under a new name")
        assertNull(accounts.findByUsername("dana"))
        assertNotNull(accounts.findByUsername("DANA.SCULLY"), "names are unique regardless of case")
        assertFailsWith<ConflictException> { accounts.updateUser(Actor.SYSTEM, renamed.id, username = "taken") }
        assertFailsWith<ConflictException> { accounts.updateUser(Actor.SYSTEM, external.id, username = "erin.local") }
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

    private companion object {
        const val CHOSEN_PASSWORD: String = "a much longer passphrase"
    }
}
