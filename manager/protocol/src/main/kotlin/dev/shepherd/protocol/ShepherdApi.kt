package dev.shepherd.protocol

/** Filters for [ShepherdApi.listDevices]; null or empty means "any". */
data class DeviceQuery(
    val state: String? = null,
    val provider: String? = null,
    val deviceType: String? = null,
    val api: String? = null,
    val labels: Map<String, String> = emptyMap(),
    /** Poll every adapter now instead of reading the manager's background snapshot. */
    val refresh: Boolean = false
)

/**
 * The session and device operations clients build on.
 *
 * Implemented over HTTP by `dev.shepherd.client.ShepherdClient` and in-process by the
 * manager itself, so the same MCP tools work against a remote manager (stdio) and inside it
 * (the `/mcp` endpoint). Every operation acts as the caller's API key.
 */
interface ShepherdApi {
    suspend fun whoAmI(): WhoAmIResponse

    suspend fun listDevices(query: DeviceQuery = DeviceQuery()): DevicesResponse

    suspend fun getDevice(id: String): DeviceDto

    suspend fun createSession(request: CreateSessionRequest): SessionResponse

    suspend fun getSession(id: String): SessionResponse

    /** [owner] `me` lists the caller's own sessions. */
    suspend fun listSessions(status: String? = null, owner: String? = null): List<SessionResponse>

    /** Long-polls a queued session for up to [timeoutSeconds] (the manager caps it at 30). */
    suspend fun waitForSession(id: String, timeoutSeconds: Long = 20): SessionResponse

    suspend fun heartbeat(id: String): SessionResponse

    suspend fun extendSession(id: String, ttlSeconds: Long): SessionResponse

    suspend fun releaseSession(id: String)
}

/** A request the manager refused. [status] is the HTTP status it answered with; the message is its explanation. */
open class ShepherdApiException(val status: Int, message: String) : RuntimeException(message)
