package dev.shepherd.infra.auth

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import com.unboundid.ldap.listener.InMemoryListenerConfig
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.model.AuthConfig
import dev.shepherd.domain.model.LdapConfig
import dev.shepherd.infra.db.ShepherdDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** LDAP sign-in against an in-memory directory with people, `memberOf` and group entries. */
class LdapSignInTest {

    @TempDir
    lateinit var tempDir: File

    private val ldap = LdapSignIn(environment = emptyMap())

    @Test
    fun `a person signs in with their directory password and brings their groups`() = runBlocking {
        val alice: ExternalIdentity = assertNotNull(ldap.authenticate(config(), "alice", "wonderland-secret"))
        val bob: ExternalIdentity = assertNotNull(ldap.authenticate(config(groupSearch = true), "bob", "builder-secret"))

        assertEquals("uid=alice,ou=people,dc=example,dc=com", alice.externalId)
        assertEquals("Alice Liddell", alice.displayName)
        assertEquals("alice@example.com", alice.email)
        assertContains(alice.groups, "qa")
        assertContains(bob.groups, "shepherd-admins")
    }

    @Test
    fun `wrong passwords, unknown people, empty passwords and filter tricks sign nobody in`() = runBlocking {
        assertNull(ldap.authenticate(config(), "alice", "not her password"))
        assertNull(ldap.authenticate(config(), "nobody", "wonderland-secret"))
        assertNull(ldap.authenticate(config(), "alice", ""))
        assertNull(ldap.authenticate(config(), "*", "wonderland-secret"))
        assertNull(ldap.authenticate(config(), "alice)(uid=*", "wonderland-secret"))
    }

    @Test
    fun `a directory that cannot be asked is an outage, not a wrong password`() = runBlocking {
        assertFailsWith<DirectoryUnavailableException> {
            ldap.authenticate(config().copy(url = "ldap://127.0.0.1:1", connectTimeoutSeconds = 1), "alice", "wonderland-secret")
        }
        assertFailsWith<DirectoryUnavailableException> {
            ldap.authenticate(config().copy(bindPassword = "wrong"), "alice", "wonderland-secret")
        }
    }

    @Test
    fun `password sign-in provisions directory people with the role their groups map to`() = runBlocking {
        val auth = AuthConfig(ldap = config(groupSearch = true))
        val database = ShepherdDatabase.sqlite(File(tempDir, "ldap.db").absolutePath)
        val webSessions = WebSessionStore(database)
        val accounts =
            Accounts(UserStore(database), UserTokenStore(database), webSessions, PasswordHasher(iterations = 1_000), authConfig = { auth })
        val signIn = SignIn(
            accounts = accounts,
            webSessions = webSessions,
            ldap = ldap,
            oidc = OidcSignIn(HttpClient(CIO), { auth }),
            throttle = LoginThrottle(maxFailures = { 5 }, lockout = { Duration.ofMinutes(15) }),
            authConfig = { auth }
        )

        val bob: UserRecord = signIn.withPassword("bob", "builder-secret", address = "10.0.0.5")
        val again: UserRecord = signIn.withPassword("bob", "builder-secret", address = "10.0.0.5")
        val wrong = assertFailsWith<SignInFailure> { signIn.withPassword("bob", "nope", address = "10.0.0.5") }

        assertEquals(UserSource.LDAP, bob.source)
        assertEquals(Role.ADMIN, bob.role)
        assertEquals(bob.id, again.id)
        assertEquals(SignIn.WRONG_CREDENTIALS, wrong.message)
    }

    private fun config(groupSearch: Boolean = false) = LdapConfig(
        url = "ldap://127.0.0.1:$port",
        bindDn = "cn=search,dc=example,dc=com",
        bindPassword = "search-secret",
        userSearchBase = "ou=people,dc=example,dc=com",
        userSearchFilter = "(uid={0})",
        groupSearchBase = if (groupSearch) "ou=groups,dc=example,dc=com" else null,
        roleMapping = mapOf("qa" to "user", "shepherd-admins" to "admin")
    )

    companion object {
        private lateinit var server: InMemoryDirectoryServer
        private var port: Int = 0

        @BeforeAll
        @JvmStatic
        fun startDirectory() {
            val config = InMemoryDirectoryServerConfig("dc=example,dc=com").apply {
                addAdditionalBindCredentials("cn=search,dc=example,dc=com", "search-secret")
                setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0))
                // memberOf is an operational attribute a real server maintains; allow writing it here.
                schema = null
            }
            server = InMemoryDirectoryServer(config)
            server.add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example")
            server.add("dn: ou=people,dc=example,dc=com", "objectClass: top", "objectClass: organizationalUnit", "ou: people")
            server.add("dn: ou=groups,dc=example,dc=com", "objectClass: top", "objectClass: organizationalUnit", "ou: groups")
            server.add(
                "dn: uid=alice,ou=people,dc=example,dc=com",
                "objectClass: inetOrgPerson",
                "uid: alice",
                "cn: Alice Liddell",
                "sn: Liddell",
                "mail: alice@example.com",
                "userPassword: wonderland-secret",
                "memberOf: cn=qa,ou=groups,dc=example,dc=com"
            )
            server.add(
                "dn: uid=bob,ou=people,dc=example,dc=com",
                "objectClass: inetOrgPerson",
                "uid: bob",
                "cn: Bob",
                "sn: Builder",
                "userPassword: builder-secret"
            )
            server.add(
                "dn: cn=shepherd-admins,ou=groups,dc=example,dc=com",
                "objectClass: groupOfNames",
                "cn: shepherd-admins",
                "member: uid=bob,ou=people,dc=example,dc=com"
            )
            server.startListening()
            port = server.listenPort
        }

        @AfterAll
        @JvmStatic
        fun stopDirectory() {
            server.shutDown(true)
        }
    }
}
