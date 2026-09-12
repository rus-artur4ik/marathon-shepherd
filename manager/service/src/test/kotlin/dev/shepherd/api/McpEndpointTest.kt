package dev.shepherd.api

import dev.shepherd.domain.auth.Role
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** The embedded MCP endpoint driven by the official MCP client over Streamable HTTP. */
class McpEndpointTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `an agent leases and returns a device through the MCP endpoint`() = testApplication {
        val services = startManager(tempDir, "mcp")
        val key = services.issueKey("agent", Role.USER)
        val client = mcpClient(key)

        val tools = client.listTools().tools.map { tool -> tool.name }
        val devices = client.call("list_devices", mapOf("api" to "34"))
        val acquired = client.call("acquire_devices", mapOf("count" to 1, "api" to "34", "purpose" to "smoke test"))
        val sessionId = assertNotNull(Regex("Session (sess_[A-Za-z0-9_-]+) is READY").find(acquired.text())).groupValues[1]
        val session = assertNotNull(services.sessionManager.getSession(sessionId))
        val mine = client.call("list_my_sessions", emptyMap())
        val released = client.call("release_session", mapOf("session_id" to sessionId))
        client.close()

        assertContains(tools, "acquire_devices")
        assertNotEquals(true, devices.isError, devices.text())
        assertNotEquals(true, acquired.isError, acquired.text())
        assertEquals("agent", session.ownerName)
        assertEquals("smoke test", session.name)
        assertEquals(900, session.idleTimeoutSeconds)
        assertEquals("mcp", session.metadata["client"])
        assertContains(mine.text(), sessionId)
        assertContains(released.text(), "released")
        assertEquals("RELEASED", services.sessionManager.getSession(sessionId)?.status?.name)
    }

    @Test
    fun `the endpoint needs an API key`() = testApplication {
        startManager(tempDir, "mcp-anonymous")

        val response = client.post(MCP_PATH) {
            contentType(ContentType.Application.Json)
            setBody(INITIALIZE)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNotNull(response.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    fun `a viewer can look at devices but not lease them`() = testApplication {
        val services = startManager(tempDir, "mcp-viewer")
        val client = mcpClient(services.issueKey("dashboard", Role.VIEWER))

        val devices = client.call("list_devices", emptyMap())
        val acquired = client.call("acquire_devices", emptyMap())
        client.close()

        assertNotEquals(true, devices.isError, devices.text())
        assertEquals(true, acquired.isError)
        assertContains(acquired.text(), "role")
    }

    @Test
    fun `msh yaml sets the guardrails and can turn the endpoint off`() = testApplication {
        val services = startManager(
            tempDir,
            "mcp-config",
            extraConfig = """
                |mcp:
                |  maxDevicesPerSession: 1
            """.trimMargin()
        )
        val key = services.issueKey("agent", Role.USER)
        val client = mcpClient(key)

        val tooMany = client.call("acquire_devices", mapOf("count" to 2))
        client.close()
        services.providerRegistry.updateConfig(
            services.providerRegistry.currentConfig().copy(mcp = services.providerRegistry.currentConfig().mcp.copy(enabled = false))
        )
        val turnedOff = this.client.post(MCP_PATH) {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody(INITIALIZE)
        }

        assertEquals(true, tooMany.isError)
        assertContains(tooMany.text(), "1 to 1 device(s)")
        assertEquals(HttpStatusCode.NotFound, turnedOff.status)
    }

    private suspend fun ApplicationTestBuilder.mcpClient(key: String): Client {
        val http = createClient { install(SSE) }
        val client = Client(Implementation(name = "test-agent", version = "1.0"))
        client.connect(StreamableHttpClientTransport(http, MCP_PATH, requestBuilder = { bearerAuth(key) }))
        return client
    }

    private suspend fun Client.call(name: String, arguments: Map<String, Any?>): CallToolResult =
        callTool(name = name, arguments = arguments)

    private fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("\n") { content -> content.text }

    private companion object {
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}"""
    }
}
