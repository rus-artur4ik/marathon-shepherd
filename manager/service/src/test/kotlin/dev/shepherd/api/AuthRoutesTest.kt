package dev.shepherd.api

import dev.shepherd.domain.auth.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `api calls without a key get a JSON 401 with a bearer challenge`() = testApplication {
        startManager(tempDir, "no-key")

        val response = client.get("/api/v1/sessions")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val challenges = response.headers.getAll(HttpHeaders.WWWAuthenticate).orEmpty()
        assertEquals(1, challenges.size, "exactly one challenge: $challenges")
        assertTrue(challenges.single().startsWith("Bearer") && "realm" in challenges.single(), challenges.single())
        assertContains(response.bodyAsText(), "Missing or invalid API key")
    }

    @Test
    fun `unknown keys are rejected`() = testApplication {
        startManager(tempDir, "bad-key")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/devices") { bearerAuth("msh_not-a-real-key") }.status)
    }

    @Test
    fun `probes, metrics and docs stay public`() = testApplication {
        startManager(tempDir, "public")

        listOf("/live", "/ready", "/metrics", "/openapi.yaml").forEach { path ->
            assertEquals(HttpStatusCode.OK, client.get(path).status, path)
        }
        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/health").status)
    }

    @Test
    fun `viewers can read the fleet but cannot take devices`() = testApplication {
        val services = startManager(tempDir, "viewer")
        val viewer = services.issueKey("dashboard", Role.VIEWER)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/sessions") { bearerAuth(viewer) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/devices") { bearerAuth(viewer) }.status)
        val create = createSession(viewer)

        assertEquals(HttpStatusCode.Forbidden, create.status)
        assertContains(create.bodyAsText(), "needs the admin or user role")
    }

    @Test
    fun `provider keys cannot use the session api and users cannot read config`() = testApplication {
        val services = startManager(tempDir, "roles")
        val provider = services.issueKey("rack-7", Role.PROVIDER)
        val user = services.issueKey("ci", Role.USER)

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/sessions") { bearerAuth(provider) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/config") { bearerAuth(user) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/clients") { bearerAuth(user) }.status)
    }
}
