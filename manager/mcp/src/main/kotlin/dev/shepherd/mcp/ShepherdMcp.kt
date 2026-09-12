package dev.shepherd.mcp

import dev.shepherd.common.BuildInfo
import dev.shepherd.protocol.ShepherdApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableDeferred

/** MCP servers whose tools lease devices through a [ShepherdApi]. */
object ShepherdMcp {
    const val SERVER_NAME: String = "marathon-shepherd"

    private const val INSTRUCTIONS: String =
        "Marathon Shepherd leases Android test devices. Typical flow: list_devices to see what exists, " +
            "acquire_devices to lease some, then use the returned adb servers (adb -H <host> -P <port> ...), " +
            "and release_session when done. Sessions expire after their lifetime and are released when you " +
            "stop calling get_session, so check in while you work."

    /** A server with the Shepherd tools and resources, acting through [api]. */
    fun server(api: ShepherdApi, guardrails: McpGuardrails = McpGuardrails(), version: String = BuildInfo.version): Server {
        val server = Server(
            Implementation(name = SERVER_NAME, version = version, title = "Marathon Shepherd"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false)
                )
            ),
            INSTRUCTIONS
        )
        ShepherdTools(api, guardrails).register(server)
        ShepherdResources(api).register(server)
        return server
    }

    /** Serves one client over [transport] until it disconnects. */
    suspend fun serve(server: Server, transport: Transport) {
        val closed = CompletableDeferred<Unit>()
        val session = server.createSession(transport)
        session.onClose { closed.complete(Unit) }
        closed.await()
    }
}
