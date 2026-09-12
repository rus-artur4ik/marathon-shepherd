package dev.shepherd.mcp

import dev.shepherd.protocol.AdbServer
import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.DeviceDto
import dev.shepherd.protocol.DeviceQuery
import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.QuotaDto
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.ShepherdApi
import dev.shepherd.protocol.ShepherdApiException
import dev.shepherd.protocol.UsageDto
import dev.shepherd.protocol.WhoAmIResponse
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/** The tools over a real MCP client and server, connected through in-memory pipes. */
class ShepherdMcpTest {

    private val api = FakeShepherdApi()

    @Test
    fun `lists the Shepherd tools with their hints`() = withClient { client ->
        val tools = client.listTools().tools

        assertEquals(
            setOf(
                "list_devices",
                "acquire_devices",
                "wait_for_session",
                "get_session",
                "list_my_sessions",
                "extend_session",
                "release_session"
            ),
            tools.map { tool -> tool.name }.toSet()
        )
        assertEquals(true, tools.single { tool -> tool.name == "list_devices" }.annotations?.readOnlyHint)
        assertEquals(true, tools.single { tool -> tool.name == "release_session" }.annotations?.destructiveHint)
    }

    @Test
    fun `acquire_devices applies the guardrails`() = withClient(
        McpGuardrails(maxDevicesPerSession = 2, defaultTtlSeconds = 600, maxTtlSeconds = 3_600, idleTimeoutSeconds = 300)
    ) { client ->
        val tooMany = client.call("acquire_devices", mapOf("count" to 3))
        val acquired = client.call(
            "acquire_devices",
            mapOf("count" to 2, "ttl_seconds" to 7_200, "purpose" to "explore the login screen", "labels" to mapOf("form" to "phone"))
        )

        assertEquals(true, tooMany.isError)
        assertContains(tooMany.text(), "1 to 2 device(s)")
        val request: CreateSessionRequest = api.created.single()
        assertEquals(2, request.maxDevices)
        assertEquals(3_600, request.ttlSeconds)
        assertEquals(300, request.idleTimeoutSeconds)
        assertEquals("explore the login screen", request.name)
        assertEquals(mapOf("form" to "phone"), request.labels)
        assertEquals(mapOf(METADATA_CLIENT to CLIENT_MCP), request.metadata)
        assertNotEquals(true, acquired.isError)
        assertContains(acquired.text(), "adb -H 10.0.0.5 -P 7600 devices")
        assertContains(acquired.text(), "capped at 3600s")
    }

    @Test
    fun `a queued acquisition waits for its devices`() = withClient { client ->
        api.queueNewSessions = true

        val acquired = client.call("acquire_devices", mapOf("wait_seconds" to 5))

        assertContains(acquired.text(), "is READY")
        assertEquals(listOf("sess_1"), api.waited)
    }

    @Test
    fun `an agent that does not wait gets the queued session and what to do next`() = withClient { client ->
        api.queueNewSessions = true

        val acquired = client.call("acquire_devices", mapOf("wait_seconds" to 0))

        assertContains(acquired.text(), "is PENDING")
        assertContains(acquired.text(), "wait_for_session")
        assertEquals(emptyList(), api.waited)
    }

    @Test
    fun `get_session keeps the session alive`() = withClient { client ->
        client.call("acquire_devices", emptyMap())

        val shown = client.call("get_session", mapOf("session_id" to "sess_1"))

        assertContains(shown.text(), "Session sess_1 is READY")
        assertEquals(listOf("sess_1"), api.heartbeats)
    }

    @Test
    fun `manager refusals come back as tool errors`() = withClient { client ->
        api.refuseWith = ShepherdApiException(429, "Device quota exhausted for 'agent'")

        val refused = client.call("acquire_devices", emptyMap())
        val missing = client.call("get_session", emptyMap())

        assertEquals(true, refused.isError)
        assertContains(refused.text(), "HTTP 429")
        assertContains(refused.text(), "Device quota exhausted")
        assertEquals(true, missing.isError)
        assertContains(missing.text(), "session_id is required")
    }

    @Test
    fun `devices, sessions and release`() = withClient { client ->
        val devices = client.call("list_devices", mapOf("state" to "available", "labels" to mapOf("form" to "phone")))
        client.call("acquire_devices", emptyMap())
        val mine = client.call("list_my_sessions", emptyMap())
        val released = client.call("release_session", mapOf("session_id" to "sess_1"))

        assertContains(devices.text(), "rack-1:R58M: available, physical, API 34, Pixel 8, labels form=phone")
        assertEquals(DeviceQuery(state = "available", labels = mapOf("form" to "phone")), api.deviceQueries.single())
        assertContains(mine.text(), "sess_1: READY")
        assertContains(released.text(), "Session sess_1 released")
        assertEquals(listOf("sess_1"), api.released)
    }

    @Test
    fun `the devices resource is JSON`() = withClient { client ->
        val result = client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri = DEVICES_URI)))

        val text = (result.contents.single() as TextResourceContents).text
        val device = Json.parseToJsonElement(text).jsonObject.getValue("devices").jsonArray.single().jsonObject
        assertEquals("rack-1:R58M", device.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `sessions an agent leaves open are released when it goes away`() = runBlocking {
        val releasing = ReleasingShepherdApi(api)
        val first = releasing.createSession(CreateSessionRequest())
        val second = releasing.createSession(CreateSessionRequest())
        releasing.releaseSession(first.id)

        val released = releasing.releaseAll()

        assertEquals(1, released)
        assertEquals(listOf(first.id, second.id), api.released)
        assertEquals(0, releasing.releaseAll())
    }

    @Test
    fun `guardrails come from the environment`() {
        val guardrails = McpGuardrails.fromEnvironment(mapOf("MSH_MCP_MAX_DEVICES" to "4", "MSH_MCP_IDLE_TIMEOUT_SECONDS" to "120"))

        assertEquals(4, guardrails.maxDevicesPerSession)
        assertEquals(120, guardrails.idleTimeoutSeconds)
        assertEquals(McpGuardrails().maxTtlSeconds, guardrails.maxTtlSeconds)
        assertFailsWith<IllegalArgumentException> { McpGuardrails.fromEnvironment(mapOf("MSH_MCP_MAX_DEVICES" to "many")) }
        assertFailsWith<IllegalArgumentException> { McpGuardrails.fromEnvironment(mapOf("MSH_MCP_IDLE_TIMEOUT_SECONDS" to "5")) }
    }

    private fun withClient(guardrails: McpGuardrails = McpGuardrails(), block: suspend (Client) -> Unit) = runBlocking {
        val toServer = PipedOutputStream()
        val serverInput = PipedInputStream(toServer, PIPE_BUFFER)
        val toClient = PipedOutputStream()
        val clientInput = PipedInputStream(toClient, PIPE_BUFFER)
        val server = ShepherdMcp.server(api, guardrails, version = "test")
        val serving = launch(Dispatchers.IO) {
            ShepherdMcp.serve(server, StdioServerTransport(serverInput.asSource().buffered(), toClient.asSink().buffered()) {})
        }
        val client = Client(Implementation(name = "test-agent", version = "1.0"))
        try {
            withTimeout(30.seconds) {
                client.connect(StdioClientTransport(clientInput.asSource().buffered(), toServer.asSink().buffered()))
                block(client)
            }
        } finally {
            client.close()
            serving.cancel()
            server.close()
        }
    }

    private suspend fun Client.call(name: String, arguments: Map<String, Any?>): CallToolResult =
        callTool(name = name, arguments = arguments)

    private fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("\n") { content -> content.text }

    private companion object {
        const val PIPE_BUFFER = 1 shl 16
    }
}

/** An in-memory manager: one rack with one device, READY sessions unless told to queue them. */
private class FakeShepherdApi : ShepherdApi {
    val created = mutableListOf<CreateSessionRequest>()
    val deviceQueries = mutableListOf<DeviceQuery>()
    val waited = mutableListOf<String>()
    val heartbeats = mutableListOf<String>()
    val released = mutableListOf<String>()
    var queueNewSessions = false
    var refuseWith: ShepherdApiException? = null
    private val sessions = LinkedHashMap<String, SessionResponse>()

    override suspend fun whoAmI(): WhoAmIResponse = WhoAmIResponse(
        id = "cl_agent",
        name = "agent",
        role = "user",
        quota = QuotaDto(maxDevices = 4),
        usage = UsageDto(activeSessions = active().size, devices = active().sumOf { session -> session.allocatedDevices })
    )

    override suspend fun listDevices(query: DeviceQuery): DevicesResponse {
        deviceQueries += query
        return DevicesResponse(
            providers = emptyList(),
            totalAvailable = 1,
            totalBusy = 0,
            devices = listOf(
                DeviceDto(
                    id = "rack-1:R58M",
                    provider = "rack-1",
                    localId = "R58M",
                    deviceType = "physical",
                    state = "available",
                    apiLevel = "34",
                    model = "Pixel 8",
                    labels = mapOf("form" to "phone")
                )
            )
        )
    }

    override suspend fun getDevice(id: String): DeviceDto = listDevices(DeviceQuery()).devices.first { device -> device.id == id }

    override suspend fun createSession(request: CreateSessionRequest): SessionResponse {
        refuseWith?.let { refusal -> throw refusal }
        created += request
        val id = "sess_${created.size}"
        val count: Int = request.resolvedMaxDevices()
        val session = SessionResponse(
            id = id,
            status = if (queueNewSessions) STATUS_PENDING else STATUS_READY,
            requestedDevices = count,
            allocatedDevices = if (queueNewSessions) 0 else count,
            adbServers = if (queueNewSessions) emptyList() else listOf(AdbServer("10.0.0.5", 7600)),
            queuePosition = if (queueNewSessions) 1 else null,
            createdAt = "2026-09-12T10:00:00Z",
            expiresAt = "2026-09-12T10:30:00Z",
            idleTimeoutSeconds = request.idleTimeoutSeconds,
            name = request.name
        )
        sessions[id] = session
        return session
    }

    override suspend fun getSession(id: String): SessionResponse = sessions[id] ?: throw ShepherdApiException(404, "Session $id not found")

    override suspend fun listSessions(status: String?, owner: String?): List<SessionResponse> = active()

    override suspend fun waitForSession(id: String, timeoutSeconds: Long): SessionResponse {
        waited += id
        val ready = getSession(id).let { session ->
            session.copy(
                status = STATUS_READY,
                allocatedDevices = session.requestedDevices,
                adbServers = listOf(AdbServer("10.0.0.5", 7600)),
                queuePosition = null
            )
        }
        sessions[id] = ready
        return ready
    }

    override suspend fun heartbeat(id: String): SessionResponse = getSession(id).also { heartbeats += id }

    override suspend fun extendSession(id: String, ttlSeconds: Long): SessionResponse = getSession(id)

    override suspend fun releaseSession(id: String) {
        val session = getSession(id)
        sessions[id] = session.copy(status = "RELEASED")
        released += id
    }

    private fun active(): List<SessionResponse> =
        sessions.values.filter { session -> session.status == STATUS_READY || session.status == STATUS_PENDING }
}
