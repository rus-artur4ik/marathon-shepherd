package dev.shepherd.infra.auth

import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.infra.db.ShepherdDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessControlTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `bootstrap creates one admin, writes its key owner-only and runs only once`() = runTest {
        val accessControl = accessControl("bootstrap")
        val file = File(tempDir, "initial-admin-token")

        val key = accessControl.bootstrap(file)
        val second = accessControl.bootstrap(file)

        assertNotNull(key)
        assertTrue(key.startsWith(ApiKeys.PREFIX))
        assertEquals(key, file.readText().trim())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
        assertEquals(Role.ADMIN, accessControl.authenticate(key)?.role)
        assertNull(second, "an existing admin must not trigger another key")
    }

    @Test
    fun `static admin token skips bootstrap and is never stored`() = runTest {
        val accessControl = accessControl("static", staticAdminToken = "s3cret-admin")
        val file = File(tempDir, "initial-admin-token")

        assertNull(accessControl.bootstrap(file))
        assertFalse(file.exists())
        assertEquals(AccessControl.STATIC_ADMIN, accessControl.authenticate("s3cret-admin"))
        assertNull(accessControl.authenticate("s3cret-admin-but-longer"))
        assertTrue(accessControl.listClients().isEmpty())
    }

    @Test
    fun `the last admin cannot be revoked or demoted`() = runTest {
        val accessControl = accessControl("last-admin")
        val firstKey = checkNotNull(accessControl.bootstrap(null))
        val first = accessControl.listClients().single()

        assertFailsWith<ConflictException> { accessControl.revokeClient(Actor.SYSTEM, first.id) }
        assertFailsWith<ConflictException> { accessControl.updateClient(Actor.SYSTEM, first.id, Role.USER, null, null) }

        accessControl.createClient(Actor.SYSTEM, "second-admin", Role.ADMIN, null, ClientQuota.UNLIMITED)
        accessControl.revokeClient(Actor.SYSTEM, first.id)

        assertNull(accessControl.authenticate(firstKey))
    }

    @Test
    fun `configured defaults fill in quotas for non-admins only`() = runTest {
        val accessControl = accessControl("defaults", defaults = ClientQuota(maxDevices = 2, maxSessionLifetimeSeconds = 7_200))
        val user = accessControl.createClient(Actor.SYSTEM, "ci", Role.USER, null, ClientQuota(maxSessionLifetimeSeconds = 60))
        val admin = accessControl.createClient(Actor.SYSTEM, "ops", Role.ADMIN, null, ClientQuota.UNLIMITED)

        assertEquals(ClientQuota(maxDevices = 2, maxSessionLifetimeSeconds = 60), accessControl.authenticate(user.apiKey)?.quota)
        assertEquals(ClientQuota.UNLIMITED, accessControl.authenticate(admin.apiKey)?.quota)
    }

    @Test
    fun `keys are stored only as hashes`() = runTest {
        val accessControl = accessControl("hashes")

        val issued = accessControl.createClient(Actor.SYSTEM, "ci", Role.USER, null, ClientQuota.UNLIMITED)

        val stored: String = tempDir.listFiles().orEmpty()
            .filter { file -> file.name.startsWith("hashes.db") }
            .joinToString("") { file -> String(file.readBytes(), Charsets.ISO_8859_1) }
        assertTrue(stored.isNotEmpty())
        assertFalse(stored.contains(issued.apiKey), "the plaintext key must never reach the database")
        assertTrue(stored.contains(ApiKeys.hash(issued.apiKey)))
    }

    private fun accessControl(
        name: String,
        staticAdminToken: String? = null,
        defaults: ClientQuota = ClientQuota.UNLIMITED
    ): AccessControl = AccessControl(
        clients = ClientStore(ShepherdDatabase.sqlite(File(tempDir, "$name.db").absolutePath)),
        quotaDefaults = { defaults },
        staticAdminToken = staticAdminToken
    )
}
