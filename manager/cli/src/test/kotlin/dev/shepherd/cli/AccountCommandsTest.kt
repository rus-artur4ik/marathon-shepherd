package dev.shepherd.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.testing.test
import dev.shepherd.client.ShepherdClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountCommandsTest {

    @TempDir
    lateinit var tempDir: File

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    @Test
    fun `login keeps a personal token owner-only and later commands use it`() {
        val credentials = File(tempDir, "credentials.json")
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/tokens" -> respondJson(ISSUED, HttpStatusCode.Created)
                "/api/v1/me" -> respondJson(WHOAMI)
                else -> respondJson("""{"error":"no"}""", HttpStatusCode.NotFound)
            }
        }

        val login = cli(handler).runCli("login --username dana --password correct-horse-battery --credentials-file ${credentials.path}")
        val whoami = cli(handler).runCli("whoami --credentials-file ${credentials.path}")

        assertEquals(0, login.statusCode, login.stderr)
        assertContains(login.stdout, "Signed in to http://localhost:6037 as dana")
        assertContains(credentials.readText(), "msh_personal")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(credentials.toPath())
        )
        val sent = Json.parseToJsonElement(bodies.first()).jsonObject
        assertEquals("dana", sent["username"]?.jsonPrimitive?.content)
        assertEquals(null, requests.first().headers[HttpHeaders.Authorization], "signing in sends no key")
        assertEquals(0, whoami.statusCode, whoami.stderr)
        assertEquals("Bearer msh_personal", requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a temporary password is explained with the command that replaces it`() {
        val unused = File(tempDir, "unused.json")
        val result = cli { respondJson(TEMPORARY_PASSWORD_REFUSAL, HttpStatusCode.Forbidden) }
            .runCli("login --username frank --password temporary-password --credentials-file ${unused.path}")

        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "mshctl passwd --username frank")
        assertFalse(unused.exists())
    }

    @Test
    fun `passwd with a username replaces a password without a key`() {
        val result = cli { respond("", HttpStatusCode.NoContent) }
            .runCli("passwd --username frank --current temporary-password --new correct-horse-battery")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("/api/v1/auth/password", requests.single().url.encodedPath)
        assertContains(bodies.single(), "correct-horse-battery")
    }

    @Test
    fun `logout revokes the saved token and forgets it`() {
        val credentials = File(tempDir, "credentials.json")
        CredentialsFile(credentials).write(SavedCredentials("http://localhost:6037", "dana", "tok_1", "msh_personal"))

        val result = cli { respondJson(REVOKED_TOKEN) }.runCli("logout --credentials-file ${credentials.path}")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("/api/v1/me/tokens/tok_1", requests.single().url.encodedPath)
        assertEquals("Bearer msh_personal", requests.single().headers[HttpHeaders.Authorization])
        assertFalse(credentials.exists())
    }

    @Test
    fun `admins create people and see the one-time password once`() {
        val result = cli { respondJson(CREATED_USER, HttpStatusCode.Created) }
            .runCli("users create --username hank --role viewer --token msh_admin")

        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "Created viewer hank (usr_2)")
        assertContains(result.stdout, "Tmp-Password-123")
        assertTrue(bodies.single().contains("\"role\":\"viewer\""), bodies.single())
    }

    /** Runs mshctl with its credentials file in the test directory, never the real one in the home directory. */
    private fun CliktCommand.runCli(argv: String, envvars: Map<String, String> = emptyMap()) =
        test(argv, envvars = mapOf("MSH_CREDENTIALS_FILE" to File(tempDir, "credentials.json").path) + envvars)

    private fun cli(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): CliktCommand {
        val engine = MockEngine { request ->
            requests += request
            (request.body as? TextContent)?.let { content -> bodies += content.text }
            handler(request)
        }
        return mshctl { managerUrl, token -> ShepherdClient(managerUrl, token, HttpClient(engine)) }
    }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        const val ISSUED =
            """{"token":{"id":"tok_1","name":"mshctl on host","prefix":"msh_pers","createdAt":"t",""" +
                """"expiresAt":"2026-12-12T00:00:00Z"},"secret":"msh_personal"}"""
        const val TEMPORARY_PASSWORD_REFUSAL =
            """{"error":"Choose a new password first: mshctl passwd, or sign in to the web UI"}"""
        const val WHOAMI =
            """{"id":"usr_1","name":"dana","role":"user","quota":{},"usage":{"activeSessions":0,"devices":0},"kind":"user"}"""
        const val REVOKED_TOKEN = """{"id":"tok_1","name":"mshctl on host","prefix":"msh_pers","createdAt":"t","revokedAt":"t"}"""
        const val CREATED_USER =
            """{"user":{"id":"usr_2","username":"hank","source":"local","role":"viewer","active":true,"mustChangePassword":true,""" +
                """"createdAt":"t"},"temporaryPassword":"Tmp-Password-123"}"""
    }
}
