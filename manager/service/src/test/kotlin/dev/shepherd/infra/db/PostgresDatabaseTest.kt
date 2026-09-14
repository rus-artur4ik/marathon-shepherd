package dev.shepherd.infra.db

import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.devices.MaintenanceInfo
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.provider.ProviderRegistration
import dev.shepherd.infra.audit.AuditQuery
import dev.shepherd.infra.audit.AuditStore
import dev.shepherd.infra.auth.ClientRecord
import dev.shepherd.infra.auth.ClientStore
import dev.shepherd.infra.devices.MaintenanceStore
import dev.shepherd.infra.providers.RegistrationStore
import dev.shepherd.infra.state.StateStore
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every store against the Postgres backend `MSH_DB_URL` selects, since SQL that SQLite accepts
 * is not automatically SQL Postgres accepts.
 */
class PostgresDatabaseTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `sessions, retention and the single-manager lock work on postgres`() = runBlocking {
        val sessions = StateStore(database)
        sessions.saveSession(session("sess_old", SessionStatus.RELEASED, ended = Instant.now().minus(Duration.ofDays(40))))
        sessions.saveSession(session("sess_live", SessionStatus.READY, ended = null))
        sessions.saveSessionLease("sess_old", "rack-1", "lease-1", count = 1)

        val pruned: Int = sessions.pruneFinishedSessions(Instant.now().minus(Duration.ofDays(30)))
        val held = InstanceLock.acquire(database, tempDir)
        val refused = assertFailsWith<IllegalStateException> { InstanceLock.acquire(database, tempDir) }
        held.close()
        InstanceLock.acquire(database, tempDir).close()

        assertTrue(database.isPostgres)
        assertTrue(sessions.ping())
        assertEquals(1, pruned)
        assertNull(sessions.getSession("sess_old"))
        assertEquals(SessionStatus.READY, sessions.getSession("sess_live")?.status)
        assertEquals(listOf("sess_live"), sessions.listSessions().map { session -> session.id })
        assertContains(refused.message.orEmpty(), "Another manager is already running")
        assertContains(database.description, "jdbc:postgresql://")
    }

    @Test
    fun `clients, audit entries, maintenance flags and registrations survive a round trip`() = runBlocking {
        val clients = ClientStore(database)
        val audit = AuditStore(database)
        val maintenance = MaintenanceStore(database)
        val registrations = RegistrationStore(database)
        val now: Instant = Instant.now()

        clients.insert(
            ClientRecord(
                id = "cl_ci",
                name = "ci",
                role = Role.USER,
                keyPrefix = "msh_ab12",
                description = "nightly builds",
                quota = ClientQuota(maxDevices = 4),
                createdAt = now,
                createdBy = "admin",
                lastUsedAt = null,
                revokedAt = null
            ),
            keyHash = "hash"
        )
        clients.touch("cl_ci", now)
        val first: Long = audit.append(now, "cl_ci", "ci", "session.create", "sess_1", "success", mapOf("devices" to "2"), "10.0.0.9")
        val second: Long = audit.append(now, null, "system", "session.expire", "sess_1", "success", emptyMap(), null)
        maintenance.set("rack-1:R58M", MaintenanceInfo(reason = "cracked screen", setBy = "admin", setAt = now))
        registrations.save(
            ProviderRegistration(
                name = "rack-1",
                url = "http://10.0.0.5:7037",
                accessHost = null,
                secret = "s3cret",
                adapterType = "adb",
                clientId = "cl_ci",
                clientName = "ci",
                registeredAt = now,
                lastSeenAt = now
            )
        )

        assertEquals("ci", clients.findByKeyHash("hash")?.name)
        assertEquals(4, clients.findById("cl_ci")?.quota?.maxDevices)
        assertEquals(listOf("ci"), clients.list(includeRevoked = false).map { client -> client.name })
        assertTrue(second > first, "the audit log hands out increasing ids")
        assertEquals(listOf("session.expire", "session.create"), audit.query(AuditQuery(limit = 10)).map { entry -> entry.action })
        assertEquals(mapOf("devices" to "2"), audit.query(AuditQuery(actionPrefix = "session.create")).single().details)
        assertEquals("cracked screen", maintenance.all().getValue("rack-1:R58M").reason)
        assertTrue(maintenance.clear("rack-1:R58M"))
        assertEquals("adb", registrations.all().single().adapterType)
        registrations.delete("rack-1")
        assertEquals(emptyList(), registrations.all())
    }

    private fun session(id: String, status: SessionStatus, ended: Instant?): Session {
        val createdAt: Instant = ended ?: Instant.now()
        return Session(
            id = id,
            status = status,
            requestedDevices = 1,
            allocatedDevices = if (status == SessionStatus.READY) 1 else 0,
            api = "34",
            deviceType = "physical",
            adbServers = listOf(AdbServer("10.0.0.5", 7600)),
            createdAt = createdAt,
            expiresAt = createdAt.plus(Duration.ofHours(1)),
            lastHeartbeatAt = createdAt,
            releasedAt = ended,
            ownerId = "cl_ci",
            ownerName = "ci"
        )
    }

    companion object {
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var database: ShepherdDatabase

        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            // PostgreSQL refuses to run as root, which is how some CI agents build (the Jenkins one
            // does). GitHub Actions runs these tests as an ordinary user.
            assumeFalse(System.getProperty("user.name") == "root", "PostgreSQL does not run as root")
            postgres = EmbeddedPostgres.builder().start()
            database = ShepherdDatabase.postgres(postgres.getJdbcUrl("postgres", "postgres"))
        }

        @AfterAll
        @JvmStatic
        fun stopPostgres() {
            if (::database.isInitialized) database.close()
            if (::postgres.isInitialized) postgres.close()
        }
    }
}
