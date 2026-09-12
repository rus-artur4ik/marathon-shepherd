package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.domain.model.McpConfig
import dev.shepherd.mcp.McpGuardrails
import dev.shepherd.mcp.ShepherdMcp
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson

const val MCP_PATH: String = "/mcp"

/**
 * The MCP endpoint (Streamable HTTP), stateless: every POST gets a server bound to the caller's
 * key, is answered with JSON, and leaves nothing behind. Sessions opened through it carry the MCP
 * guardrails, so their idle timeout reclaims them once an agent stops checking in.
 */
fun Route.mcpRoutes(services: ManagerServices) {
    route(MCP_PATH) {
        // JSON-RPC messages need the SDK's own JSON settings, not the API's.
        install(ContentNegotiation) { json(McpJson) }

        authenticate(API_AUTH) {
            post {
                val config: McpConfig = services.providerRegistry.currentConfig().mcp
                if (!config.enabled) {
                    call.respondError(HttpStatusCode.NotFound, "The MCP endpoint is turned off (mcp.enabled in msh.yaml)")
                    return@post
                }
                val server = ShepherdMcp.server(LocalShepherdApi(services, call.actor()), config.guardrails())
                val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
                transport.setSessionIdGenerator(null)
                server.createSession(transport)
                try {
                    transport.handleRequest(null, call)
                } finally {
                    server.close()
                }
            }
        }
    }
}

private fun McpConfig.guardrails(): McpGuardrails = McpGuardrails(
    maxDevicesPerSession = maxDevicesPerSession,
    defaultTtlSeconds = defaultTtlSeconds,
    maxTtlSeconds = maxTtlSeconds,
    idleTimeoutSeconds = idleTimeoutSeconds,
    maxWaitSeconds = maxWaitSeconds
)
