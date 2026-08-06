package dev.shepherd.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val DEFAULT_MANAGER_URL = "http://localhost:6037"
private val jsonCodec = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

class MshCtl : CliktCommand(name = "mshctl") {
    override fun run() = Unit
}

abstract class BaseCommand(name: String) : CliktCommand(name = name) {
    protected val managerUrl: String by option("--manager", "-s")
        .default(resolveManagerUrl())
    protected val jsonOutput: Boolean by option("--json").flag(default = false)

    protected suspend fun executeRequest(request: suspend (HttpClient) -> HttpResponse): String {
        val client = createClient()
        try {
            val response = request(client)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw CliktError(parseErrorMessage(body))
            }
            return body
        } finally {
            client.close()
        }
    }
}

class SessionCreate : BaseCommand("create") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Create a new test session"

    private val devices: Int by option("--devices", "-d").int().default(1)
    private val apiLevel: String by option("--api").default("34")
    private val ttl: Long by option("--ttl").long().default(3600)
    private val deviceType: String? by option("--device-type")

    override fun run() = runBlocking {
        val body = executeRequest { client ->
            client.post("$managerUrl/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(CreateRequest(devices, apiLevel, ttl, deviceType))
            }
        }
        if (jsonOutput) {
            echo(body)
            return@runBlocking
        }
        val session = jsonCodec.decodeFromString<SessionResponse>(body)
        echo(formatSession(session))
    }
}

class SessionShow : BaseCommand("show") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Show details for a session"

    private val sessionId: String by option("--id").required()

    override fun run() = runBlocking {
        val body = executeRequest { client ->
            client.get("$managerUrl/api/v1/sessions/$sessionId")
        }
        if (jsonOutput) {
            echo(body)
            return@runBlocking
        }
        val session = jsonCodec.decodeFromString<SessionResponse>(body)
        echo(formatSession(session))
    }
}

class SessionRelease : BaseCommand("release") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Release a test session"

    private val sessionId: String by option("--id").required()

    override fun run() = runBlocking {
        val body = executeRequest { client ->
            client.delete("$managerUrl/api/v1/sessions/$sessionId")
        }
        if (jsonOutput) {
            echo(body)
            return@runBlocking
        }
        val status = jsonCodec.decodeFromString<StatusResponse>(body)
        echo("Session $sessionId ${status.status}.")
    }
}

class SessionList : BaseCommand("list") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "List active sessions"

    override fun run() = runBlocking {
        val body = executeRequest { client ->
            client.get("$managerUrl/api/v1/sessions")
        }
        if (jsonOutput) {
            echo(body)
            return@runBlocking
        }
        val sessions = jsonCodec.decodeFromString<List<SessionResponse>>(body)
        echo(formatSessions(sessions))
    }
}

class DeviceStatus : BaseCommand("devices") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Show device provider status"

    override fun run() = runBlocking {
        val body = executeRequest { client ->
            client.get("$managerUrl/api/v1/devices")
        }
        if (jsonOutput) {
            echo(body)
            return@runBlocking
        }
        val devices = jsonCodec.decodeFromString<DevicesResponse>(body)
        echo(formatDevices(devices))
    }
}

class HealthCheck : BaseCommand("health") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Check manager readiness and provider health"

    override fun run() = runBlocking {
        val body = executeRequest { client ->
            client.get("$managerUrl/health")
        }
        if (jsonOutput) {
            echo(body)
            return@runBlocking
        }
        val health = jsonCodec.decodeFromString<HealthResponse>(body)
        echo(
            "Health: ${health.status} | version ${health.version} | " +
                "${health.providersHealthy}/${health.providersTotal} provider(s) healthy"
        )
    }
}

@Serializable
private data class CreateRequest(
    val devices: Int,
    val apiLevel: String,
    val ttlSeconds: Long,
    val deviceType: String? = null
)

@Serializable
private data class StatusResponse(val status: String)

@Serializable
private data class ErrorResponse(val error: String? = null)

@Serializable
private data class SessionResponse(
    val id: String,
    val status: String,
    val requestedDevices: Int,
    val allocatedDevices: Int,
    val adbServers: List<AdbServerResponse>,
    val createdAt: String,
    val expiresAt: String
)

@Serializable
private data class AdbServerResponse(
    val host: String,
    val port: Int
)

@Serializable
private data class DevicesResponse(
    val providers: List<ProviderStatusResponse>,
    val totalAvailable: Int,
    val totalBusy: Int
)

@Serializable
private data class ProviderStatusResponse(
    val name: String,
    val adbHost: String,
    val adbPort: Int,
    val access: AdapterAccessResponse = AdapterAccessResponse(),
    val capabilities: AdapterCapabilitiesResponse = AdapterCapabilitiesResponse(),
    val inventory: List<AdapterDeviceProfileResponse> = emptyList(),
    val pool: PoolStatus,
    val status: String
)

@Serializable
private data class PoolStatus(
    val available: Int,
    val busy: Int,
    val total: Int
)

@Serializable
private data class HealthResponse(
    val status: String,
    val version: String,
    val providersTotal: Int,
    val providersHealthy: Int
)

@Serializable
private data class AdapterAccessResponse(
    val preferredConnectionId: String? = null,
    val connections: List<AdapterConnectionResponse> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
private data class AdapterConnectionResponse(
    val id: String,
    val protocol: String,
    val transport: String,
    val host: String,
    val port: Int,
    val exposure: String,
    val auth: AdapterConnectionAuthResponse = AdapterConnectionAuthResponse(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
private data class AdapterConnectionAuthResponse(
    val type: String = "network",
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
private data class AdapterCapabilitiesResponse(
    val supportedDeviceTypes: List<String> = emptyList(),
    val supportedApiLevels: List<String> = emptyList(),
    val supportsSelectiveApiAllocation: Boolean = false,
    val supportsTestAccessAdb: Boolean = true,
    val supportsTestAccessGrpc: Boolean = false,
    val supportsTestAccessConsole: Boolean = false,
    val features: List<String> = emptyList()
)

@Serializable
private data class AdapterDeviceProfileResponse(
    val deviceType: String,
    val apiLevel: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val abi: String? = null,
    val count: Int
)

private fun createClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(jsonCodec)
    }
}

private fun resolveManagerUrl(): String {
    return System.getenv("MSH_URL")
        ?.takeIf { value -> value.isNotBlank() }
        ?: DEFAULT_MANAGER_URL
}

private fun parseErrorMessage(body: String): String {
    if (body.isBlank()) {
        return "Request failed with an empty response body."
    }
    return runCatching { jsonCodec.decodeFromString<ErrorResponse>(body).error }
        .getOrNull()
        ?.takeIf { errorMessage -> errorMessage.isNotBlank() }
        ?: body
}

private fun formatSession(session: SessionResponse): String {
    val adbServers = if (session.adbServers.isEmpty()) {
        "none"
    } else {
        session.adbServers.joinToString(", ") { adbServer -> "${adbServer.host}:${adbServer.port}" }
    }
    return buildString {
        appendLine("Session ${session.id} (${session.status})")
        appendLine("Devices: ${session.allocatedDevices}/${session.requestedDevices}")
        appendLine("Created: ${session.createdAt}")
        appendLine("Expires: ${session.expiresAt}")
        append("ADB servers: $adbServers")
    }
}

private fun formatSessions(sessions: List<SessionResponse>): String {
    if (sessions.isEmpty()) {
        return "No active sessions."
    }
    return buildString {
        sessions.forEachIndexed { index, session ->
            if (index > 0) {
                appendLine()
            }
            append(formatSession(session))
        }
    }
}

private fun formatDevices(devices: DevicesResponse): String {
    return buildString {
        appendLine("Device providers")
        devices.providers.forEach { provider ->
            val preferredConnection = provider.access.connections.firstOrNull { connection ->
                connection.id == provider.access.preferredConnectionId
            } ?: provider.access.connections.firstOrNull()
            val accessSummary = if (preferredConnection == null) {
                "no published access"
            } else {
                "${preferredConnection.protocol}/${preferredConnection.transport} " +
                    "${preferredConnection.host}:${preferredConnection.port} " +
                    "[${preferredConnection.exposure}, auth=${preferredConnection.auth.type}]"
            }
            val deviceTypes = provider.capabilities.supportedDeviceTypes.ifEmpty { provider.inventory.map { it.deviceType }.distinct() }
            val supportedApiLevels = provider.capabilities.supportedApiLevels
            val inventorySummary = if (provider.inventory.isEmpty()) {
                "inventory unavailable"
            } else {
                provider.inventory.joinToString("; ") { profile ->
                    buildString {
                        append("${profile.deviceType}")
                        profile.apiLevel?.let { apiLevel -> append(" api=$apiLevel") }
                        profile.manufacturer?.let { manufacturer -> append(" $manufacturer") }
                        profile.model?.let { model -> append(" $model") }
                        profile.abi?.let { abi -> append(" abi=$abi") }
                        append(" x${profile.count}")
                    }
                }
            }
            appendLine(
                "- ${provider.name}: ${provider.status} | " +
                    "available ${provider.pool.available}, busy ${provider.pool.busy}, total ${provider.pool.total} | " +
                    accessSummary
            )
            appendLine(
                "  deviceTypes=${deviceTypes.joinToString(", ").ifBlank { "unknown" }} | " +
                    "supportedApiLevels=${supportedApiLevels.joinToString(", ").ifBlank { "unspecified" }} | " +
                    "selectiveApiAllocation=${provider.capabilities.supportsSelectiveApiAllocation}"
            )
            appendLine(
                "  testAccess=adb:${provider.capabilities.supportsTestAccessAdb}, " +
                    "grpc:${provider.capabilities.supportsTestAccessGrpc}, " +
                    "console:${provider.capabilities.supportsTestAccessConsole}"
            )
            appendLine("  $inventorySummary")
        }
        append("Totals: available ${devices.totalAvailable}, busy ${devices.totalBusy}")
    }
}

fun main(args: Array<String>) {
    MshCtl()
        .subcommands(SessionCreate(), SessionShow(), SessionRelease(), SessionList(), DeviceStatus(), HealthCheck())
        .main(args)
}
