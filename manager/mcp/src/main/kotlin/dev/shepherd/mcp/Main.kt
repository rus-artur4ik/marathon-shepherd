package dev.shepherd.mcp

import dev.shepherd.client.ShepherdClient
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

private const val DEFAULT_MANAGER_URL = "http://localhost:6037"

/**
 * `shepherd-mcp`: the Shepherd tools over stdio, for agents that start MCP servers as local
 * processes. It calls the manager at MSH_URL with the key in MSH_TOKEN and releases the sessions
 * it opened when the agent disconnects or the process is stopped.
 *
 * stdout carries the protocol, so everything else goes to stderr.
 */
fun main() {
    // The protocol owns stdout, so take it before anything else can print to it and send
    // everything the process writes to System.out (a library banner, a stray println) to stderr.
    val protocolOutput: OutputStream = FileOutputStream(FileDescriptor.out)
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

    val environment: Map<String, String> = System.getenv()
    val managerUrl: String = environment["MSH_URL"]?.takeIf { url -> url.isNotBlank() } ?: DEFAULT_MANAGER_URL
    val token: String = environment["MSH_TOKEN"]?.takeIf { key -> key.isNotBlank() } ?: fail(
        "set MSH_TOKEN to an API key with the user role, and MSH_URL to the manager (default $DEFAULT_MANAGER_URL)"
    )
    val guardrails: McpGuardrails = try {
        McpGuardrails.fromEnvironment(environment)
    } catch (invalid: IllegalArgumentException) {
        fail(invalid.message ?: "invalid MSH_MCP_* settings")
    }

    ShepherdClient(managerUrl, token).use { client ->
        val api = ReleasingShepherdApi(client)
        // Hosts usually close stdin when they are done, but some just stop the process.
        Runtime.getRuntime().addShutdownHook(Thread { runBlocking { api.releaseAll() } })
        runBlocking {
            announce(client, managerUrl)
            val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOutput.asSink().buffered()) {}
            ShepherdMcp.serve(ShepherdMcp.server(api, guardrails), transport)
            val released: Int = api.releaseAll()
            if (released > 0) {
                log("released $released session(s) the agent left open")
            }
        }
    }
}

private suspend fun announce(client: ShepherdClient, managerUrl: String) {
    try {
        val me = client.whoAmI()
        log("serving $managerUrl as '${me.name}' (${me.role})")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Keep serving: the manager may come back, and every tool call reports its own failure.
        log("cannot check the API key against $managerUrl yet: ${error.message}")
    }
}

private fun log(message: String) = System.err.println("shepherd-mcp: $message")

private fun fail(message: String): Nothing {
    log(message)
    exitProcess(2)
}
