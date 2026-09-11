package dev.shepherd.api

import dev.shepherd.domain.auth.Role
import dev.shepherd.protocol.AuditPage
import dev.shepherd.protocol.ClientDto
import dev.shepherd.protocol.ClientKeyResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `client lifecycle - create, use, rotate, update and revoke`() = testApplication {
        startManager(tempDir, "lifecycle")

        val created = admin("POST", "/api/v1/admin/clients", """{"name":"jenkins","role":"user","quota":{"maxDevices":4}}""")
        val issued: ClientKeyResponse = TestJson.decodeFromString(created.bodyAsText())
        val id = issued.client.id
        val listing = admin("GET", "/api/v1/admin/clients").bodyAsText()
        val meBefore = client.get("/api/v1/me") { bearerAuth(issued.apiKey) }

        val rotated: ClientKeyResponse = TestJson.decodeFromString(admin("POST", "/api/v1/admin/clients/$id/rotate").bodyAsText())
        val oldKeyAfterRotate = client.get("/api/v1/me") { bearerAuth(issued.apiKey) }
        val updated: ClientDto = TestJson.decodeFromString(
            admin("PATCH", "/api/v1/admin/clients/$id", """{"role":"viewer","quota":{"maxDevices":1}}""").bodyAsText()
        )
        val revoked = admin("DELETE", "/api/v1/admin/clients/$id")
        val newKeyAfterRevoke = client.get("/api/v1/me") { bearerAuth(rotated.apiKey) }
        val active: List<ClientDto> = TestJson.decodeFromString(admin("GET", "/api/v1/admin/clients").bodyAsText())
        val all: List<ClientDto> = TestJson.decodeFromString(admin("GET", "/api/v1/admin/clients?includeRevoked=true").bodyAsText())

        assertEquals(HttpStatusCode.Created, created.status)
        assertTrue(issued.apiKey.startsWith("msh_"))
        assertEquals(4, issued.client.quota.maxDevices)
        assertContains(listing, issued.client.keyPrefix)
        assertFalse(listing.contains(issued.apiKey), "a listing must never contain a key")
        assertEquals(HttpStatusCode.OK, meBefore.status)
        assertContains(meBefore.bodyAsText(), "\"name\": \"jenkins\"")
        assertEquals(HttpStatusCode.Unauthorized, oldKeyAfterRotate.status)
        assertEquals("viewer", updated.role)
        assertEquals(1, updated.quota.maxDevices)
        assertEquals(HttpStatusCode.OK, revoked.status)
        assertEquals(HttpStatusCode.Unauthorized, newKeyAfterRevoke.status)
        assertTrue(active.none { it.id == id })
        assertFalse(all.single { it.id == id }.active)
    }

    @Test
    fun `invalid and duplicate clients are rejected`() = testApplication {
        startManager(tempDir, "invalid")

        admin("POST", "/api/v1/admin/clients", """{"name":"ci"}""")
        val duplicate = admin("POST", "/api/v1/admin/clients", """{"name":"ci"}""")
        val badRole = admin("POST", "/api/v1/admin/clients", """{"name":"ci-2","role":"superuser"}""")
        val badName = admin("POST", "/api/v1/admin/clients", """{"name":"not a name!"}""")
        val badQuota = admin("POST", "/api/v1/admin/clients", """{"name":"ci-3","quota":{"maxDevices":0}}""")

        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals(HttpStatusCode.BadRequest, badRole.status)
        assertContains(badRole.bodyAsText(), "Unknown role")
        assertEquals(HttpStatusCode.BadRequest, badName.status)
        assertEquals(HttpStatusCode.BadRequest, badQuota.status)
    }

    @Test
    fun `revoking with releaseSessions frees the client's devices`() = testApplication {
        val services = startManager(tempDir, "revoke-release")
        val ci = services.issueKey("ci", Role.USER)
        val session = createSession(ci).session()
        val clientId = services.accessControl.listClients().single { it.name == "ci" }.id

        admin("DELETE", "/api/v1/admin/clients/$clientId?releaseSessions=true")
        val after = TestJson.decodeFromString<dev.shepherd.protocol.SessionResponse>(
            admin("GET", "/api/v1/sessions/${session.id}").bodyAsText()
        )

        assertEquals("RELEASED", after.status)
    }

    @Test
    fun `audit log shows admins everything and users only their own entries`() = testApplication {
        val services = startManager(tempDir, "audit")
        val alice = services.issueKey("alice")
        createSession(alice)
        admin("POST", "/api/v1/admin/clients", """{"name":"bob"}""")

        val adminView: AuditPage = TestJson.decodeFromString(admin("GET", "/api/v1/audit").bodyAsText())
        val aliceView: AuditPage = TestJson.decodeFromString(client.get("/api/v1/audit") { bearerAuth(alice) }.bodyAsText())
        val firstPage: AuditPage = TestJson.decodeFromString(admin("GET", "/api/v1/audit?limit=2").bodyAsText())
        val secondPage: AuditPage = TestJson.decodeFromString(
            admin("GET", "/api/v1/audit?limit=2&before=${firstPage.nextBefore}").bodyAsText()
        )

        assertTrue(adminView.entries.any { it.action == "client.create" && it.target == "bob" })
        assertTrue(adminView.entries.any { it.action == "session.create" && it.actor == "alice" })
        assertEquals(setOf("alice"), aliceView.entries.map { it.actor }.toSet())
        assertEquals(2, firstPage.entries.size)
        assertTrue(secondPage.entries.all { it.id < firstPage.entries.last().id })
        assertNull(secondPage.nextBefore.takeIf { secondPage.entries.size < 2 })
    }

    private suspend fun ApplicationTestBuilder.admin(method: String, path: String, body: String? = null): HttpResponse {
        val configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(TEST_ADMIN_TOKEN)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> client.get(path, configure)
            "POST" -> client.post(path, configure)
            "PATCH" -> client.patch(path, configure)
            "DELETE" -> client.delete(path, configure)
            else -> error("unsupported method $method")
        }
    }
}
