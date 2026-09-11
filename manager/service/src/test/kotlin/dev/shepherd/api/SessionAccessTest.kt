package dev.shepherd.api

import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.protocol.AuditPage
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.WhoAmIResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SessionAccessTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `only the owner or an admin can release a session, and denials are audited`() = testApplication {
        val services = startManager(tempDir, "ownership")
        val alice = services.issueKey("alice")
        val bob = services.issueKey("bob")
        val session = createSession(alice).session()

        val bobRelease = client.delete("/api/v1/sessions/${session.id}") { bearerAuth(bob) }
        val adminRelease = client.delete("/api/v1/sessions/${session.id}") { bearerAuth(TEST_ADMIN_TOKEN) }
        val audit: AuditPage = TestJson.decodeFromString(
            client.get("/api/v1/audit?action=session.release") { bearerAuth(TEST_ADMIN_TOKEN) }.bodyAsText()
        )

        assertEquals("alice", session.owner)
        assertEquals(HttpStatusCode.Forbidden, bobRelease.status)
        assertContains(bobRelease.bodyAsText(), "belongs to alice")
        assertEquals(HttpStatusCode.OK, adminRelease.status)
        assertEquals(listOf("admin-token" to "success", "bob" to "denied"), audit.entries.map { it.actor to it.outcome })
    }

    @Test
    fun `owner=me lists only the caller's sessions`() = testApplication {
        val services = startManager(tempDir, "owner-filter")
        val alice = services.issueKey("alice")
        val bob = services.issueKey("bob")
        val aliceSession = createSession(alice).session()
        createSession(bob)

        val mine: List<SessionResponse> = TestJson.decodeFromString(
            client.get("/api/v1/sessions?owner=me") { bearerAuth(alice) }.bodyAsText()
        )
        val everyone: List<SessionResponse> = TestJson.decodeFromString(
            client.get("/api/v1/sessions") { bearerAuth(alice) }.bodyAsText()
        )

        assertEquals(listOf(aliceSession.id), mine.map { it.id })
        assertEquals(setOf("alice", "bob"), everyone.mapNotNull { it.owner }.toSet())
    }

    @Test
    fun `device quota trims a request, then refuses until devices are returned`() = testApplication {
        val services = startManager(tempDir, "quota")
        val ci = services.issueKey("ci", quota = ClientQuota(maxDevices = 3))

        val first = createSession(ci, maxDevices = 2)
        val second = createSession(ci, maxDevices = 5)
        val refused = createSession(ci, maxDevices = 1)
        client.delete("/api/v1/sessions/${first.session().id}") { bearerAuth(ci) }
        val afterRelease = createSession(ci, maxDevices = 1)

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(1, second.session().requestedDevices, "maxDevices is trimmed to what the quota leaves")
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertContains(refused.bodyAsText(), "Device quota exhausted")
        assertEquals(HttpStatusCode.Created, afterRelease.status)
    }

    @Test
    fun `lifetime cap shortens long TTLs`() = testApplication {
        val services = startManager(tempDir, "lifetime")
        val ci = services.issueKey("ci", quota = ClientQuota(maxSessionLifetimeSeconds = 600))

        val session = createSession(ci, ttlSeconds = 3_600).session()

        assertEquals(Duration.ofSeconds(600), Duration.between(Instant.parse(session.createdAt), Instant.parse(session.expiresAt)))
    }

    @Test
    fun `configured default quota applies to users but not to admins`() = testApplication {
        val services = startManager(
            tempDir,
            "defaults",
            extraConfig = """
                |quotas:
                |  defaults:
                |    maxDevices: 1
            """.trimMargin()
        )
        val user = services.issueKey("ci", Role.USER)

        createSession(user)
        val refused = createSession(user)
        val adminFirst = createSession(TEST_ADMIN_TOKEN)
        val adminSecond = createSession(TEST_ADMIN_TOKEN)
        val me: WhoAmIResponse = TestJson.decodeFromString(client.get("/api/v1/me") { bearerAuth(user) }.bodyAsText())

        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals(HttpStatusCode.Created, adminFirst.status)
        assertEquals(HttpStatusCode.Created, adminSecond.status)
        assertEquals(1, me.quota.maxDevices)
        assertEquals(1, me.usage.devices)
    }
}
