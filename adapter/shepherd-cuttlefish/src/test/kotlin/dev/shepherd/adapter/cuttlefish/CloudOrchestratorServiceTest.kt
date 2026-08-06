package dev.shepherd.adapter.cuttlefish

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class CloudOrchestratorServiceTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `should create and release instances through legacy host api`() = runBlocking {
        val backend = FakeLegacyCloudOrchestratorBackend(initialInstances = 2)
        val baseUrl = startServer { exchange -> backend.handle(exchange) }
        val leaseStore = InMemoryCloudOrchestratorLeaseStore()
        val service = CloudOrchestratorService(
            orchestratorUrl = baseUrl,
            httpClient = buildCloudOrchestratorHttpClient(),
            leaseStore = leaseStore
        )

        val acquire = service.createInstances(count = 2, apiLevel = "34", ttlSeconds = 120)

        assertNotNull(acquire.leaseId)
        assertEquals(2, acquire.acquiredCount)
        assertEquals("aosp_cf_x86_64_phone-android14-userdebug", backend.lastBuildTarget)
        assertEquals(4, service.listRunningInstances())
        assertEquals(2, service.activeLeaseCount())

        val released = service.stopInstances(requireNotNull(acquire.leaseId))

        assertTrue(released)
        assertEquals(2, service.listRunningInstances())
        assertEquals(0, service.activeLeaseCount())
    }

    @Test
    fun `should create and release instances through cloud orchestrator v1 api`() = runBlocking {
        val backend = FakeCloudV1Backend()
        val baseUrl = startServer { exchange -> backend.handle(exchange) }
        val leaseStore = InMemoryCloudOrchestratorLeaseStore()
        val service = CloudOrchestratorService(
            orchestratorUrl = baseUrl,
            basicUsername = "shepherd-cuttlefish",
            httpClient = buildCloudOrchestratorHttpClient(),
            leaseStore = leaseStore
        )

        assertTrue(service.isHealthy())
        assertEquals(0, service.listRunningInstances())

        val acquire = service.createInstances(count = 2, apiLevel = "35", ttlSeconds = 120)

        assertNotNull(acquire.leaseId)
        assertEquals(2, acquire.acquiredCount)
        assertEquals("aosp_cf_x86_64_phone-trunk_staging-userdebug", backend.lastBuildTarget)
        assertTrue(backend.observedBasicAuthUsers.isNotEmpty())
        assertTrue(backend.observedBasicAuthUsers.all { username -> username == "shepherd-cuttlefish" })
        assertTrue(backend.hostCreateWaitCalls > 0)
        assertTrue(backend.groupCreateWaitCalls > 0)
        assertTrue(backend.hostProxyReadinessChecks > 0)
        assertEquals(2, service.listRunningInstances())

        val released = service.stopInstances(requireNotNull(acquire.leaseId))

        assertTrue(released)
        assertEquals(0, service.listRunningInstances())
        assertEquals(0, service.activeLeaseCount())
    }

    @Test
    fun `should return zero allocation when cloud v1 host creation fails`() = runBlocking {
        val backend = FakeCloudV1Backend(failHostCreate = true)
        val baseUrl = startServer { exchange -> backend.handle(exchange) }
        val service = CloudOrchestratorService(
            orchestratorUrl = baseUrl,
            basicUsername = "shepherd-cuttlefish",
            httpClient = buildCloudOrchestratorHttpClient()
        )

        val acquire = service.createInstances(count = 1, apiLevel = "34", ttlSeconds = 120)

        assertEquals(null, acquire.leaseId)
        assertEquals(0, acquire.acquiredCount)
        assertEquals("", acquire.group)
        assertEquals(0, service.activeLeaseCount())
    }

    @Test
    fun `should return zero allocation when cloud v1 wait payload misses group information`() = runBlocking {
        val backend = FakeCloudV1Backend(waitPayloadMissingGroup = true)
        val baseUrl = startServer { exchange -> backend.handle(exchange) }
        val service = CloudOrchestratorService(
            orchestratorUrl = baseUrl,
            basicUsername = "shepherd-cuttlefish",
            httpClient = buildCloudOrchestratorHttpClient()
        )

        val acquire = service.createInstances(count = 2, apiLevel = "35", ttlSeconds = 120)

        assertEquals(null, acquire.leaseId)
        assertEquals(0, acquire.acquiredCount)
        assertEquals("", acquire.group)
        assertEquals(0, service.activeLeaseCount())
    }

    @Test
    fun `should treat empty cloud v1 zone list as unhealthy`() = runBlocking {
        val backend = FakeCloudV1Backend(zones = emptyList())
        val baseUrl = startServer { exchange -> backend.handle(exchange) }
        val service = CloudOrchestratorService(
            orchestratorUrl = baseUrl,
            basicUsername = "shepherd-cuttlefish",
            httpClient = buildCloudOrchestratorHttpClient()
        )

        assertEquals(false, service.isHealthy())
        assertEquals(0, service.listRunningInstances())

        val acquire = service.createInstances(count = 1, apiLevel = "34", ttlSeconds = 120)
        assertEquals(0, acquire.acquiredCount)
    }

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/") { exchange -> handler(exchange) }
        httpServer.start()
        server = httpServer
        return "http://127.0.0.1:${httpServer.address.port}"
    }
}

private class FakeLegacyCloudOrchestratorBackend(initialInstances: Int) {
    private val json = Json { ignoreUnknownKeys = true }
    private val groups = ConcurrentHashMap<String, Int>(mapOf("bootstrap" to initialInstances))
    var lastBuildTarget: String? = null
        private set

    fun handle(exchange: HttpExchange) {
        when {
            exchange.requestMethod == "GET" && exchange.requestURI.path == "/health" ->
                respondJson(exchange, 200, """{"status":"ok"}""")
            exchange.requestMethod == "GET" && exchange.requestURI.path == "/devices" ->
                respondJson(exchange, 200, devicesJson())
            exchange.requestMethod == "POST" && exchange.requestURI.path == "/cvds" ->
                handleCreate(exchange)
            exchange.requestMethod == "DELETE" && exchange.requestURI.path.startsWith("/cvds/") ->
                handleDelete(exchange)
            else -> respondJson(exchange, 404, """{"error":"not found"}""")
        }
    }

    private fun devicesJson(): String {
        return groups.entries
            .sortedBy { entry -> entry.key }
            .flatMap { entry ->
                (1..entry.value).map { index ->
                    """{"name":"${entry.key}-$index","group":"${entry.key}","status":"Running"}"""
                }
            }
            .joinToString(prefix = "[", postfix = "]")
    }

    private fun handleCreate(exchange: HttpExchange) {
        val body: String = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        val payload: JsonObject = json.parseToJsonElement(body) as JsonObject
        val cvd: JsonObject = payload.getValue("cvd") as JsonObject
        val buildSource: JsonObject = cvd.getValue("build_source") as JsonObject
        val systemBuildSource: JsonObject = buildSource.getValue("system_build_source") as JsonObject
        lastBuildTarget = (systemBuildSource.getValue("build_target") as JsonPrimitive).content
        val additionalInstances: Int = (payload.getValue("additional_instances_num") as JsonPrimitive).content.toInt()
        val group: String = "group_${groups.size + 1}"
        groups[group] = additionalInstances + 1
        respondJson(exchange, 200, """{"name":"$group"}""")
    }

    private fun handleDelete(exchange: HttpExchange) {
        val group: String = exchange.requestURI.path.removePrefix("/cvds/").ifBlank { "missing" }
        groups.remove(group)
        respondJson(exchange, 200, """{"status":"deleted"}""")
    }
}

private class FakeCloudV1Backend(
    private val zones: List<String> = listOf("local"),
    private val failHostCreate: Boolean = false,
    private val waitPayloadMissingGroup: Boolean = false
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val groupsByHost = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private var hostExists: Boolean = false
    private var hostProxyReady: Boolean = false
    private var pendingHostCreate: Boolean = false
    private val pendingGroupCreateOperations = ConcurrentHashMap<String, Pair<String, Int>>()
    var lastBuildTarget: String? = null
        private set
    val observedBasicAuthUsers: MutableList<String> = mutableListOf()
    var hostCreateWaitCalls: Int = 0
        private set
    var groupCreateWaitCalls: Int = 0
        private set
    var hostProxyReadinessChecks: Int = 0
        private set

    fun handle(exchange: HttpExchange) {
        if (!authenticate(exchange)) {
            return
        }
        when {
            exchange.requestMethod == "GET" && exchange.requestURI.path == "/v1/zones" ->
                respondJson(
                    exchange,
                    200,
                    zones.joinToString(prefix = "{\"items\":[", postfix = "]}") { zone -> "{\"name\":\"$zone\"}" }
                )
            exchange.requestMethod == "GET" && exchange.requestURI.path == "/v1/zones/local/hosts" ->
                respondJson(exchange, 200, hostsPayload())
            exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/zones/local/hosts" ->
                handleCreateHost(exchange)
            exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/zones/local/operations/create_host_1/:wait" ->
                handleWaitCreateHost(exchange)
            exchange.requestMethod == "GET" && exchange.requestURI.path == "/v1/zones/local/hosts/host-1/cvds" ->
                handleListCvds(exchange)
            exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/zones/local/hosts/host-1/cvds" ->
                handleCreateCvds(exchange)
            exchange.requestMethod == "POST" &&
                exchange.requestURI.path.startsWith("/v1/zones/local/hosts/host-1/operations/create_cvd_") ->
                handleWaitCreateCvds(exchange)
            exchange.requestMethod == "DELETE" && exchange.requestURI.path.startsWith("/v1/zones/local/hosts/host-1/groups/") ->
                handleDeleteGroup(exchange)
            else -> respondJson(exchange, 404, """{"error":"not found"}""")
        }
    }

    private fun authenticate(exchange: HttpExchange): Boolean {
        val authorizationHeader: String = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        if (!authorizationHeader.startsWith("Basic ")) {
            exchange.responseHeaders.add("WWW-Authenticate", "Basic")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return false
        }
        val encodedCredentials: String = authorizationHeader.removePrefix("Basic ").trim()
        val decodedCredentials: String = String(Base64.getDecoder().decode(encodedCredentials), StandardCharsets.UTF_8)
        val username: String = decodedCredentials.substringBefore(":")
        observedBasicAuthUsers += username
        return true
    }

    private fun hostsPayload(): String {
        if (!hostExists) {
            return """{"items":[]}"""
        }
        return """{"items":[{"name":"host-1","docker":{"image_name":"host-orchestrator","ip_address":"127.0.0.1"}}]}"""
    }

    private fun cvdsPayload(host: String): String {
        val groups: Map<String, Int> = groupsByHost[host].orEmpty()
        val cvdsJson: String = groups.entries
            .sortedBy { entry -> entry.key }
            .flatMap { entry ->
                (1..entry.value).map { index ->
                    """{"name":"${entry.key}-$index","group":"${entry.key}","status":"Running"}"""
                }
            }
            .joinToString(prefix = "[", postfix = "]")
        return """{"cvds":$cvdsJson}"""
    }

    private fun handleCreateHost(exchange: HttpExchange) {
        if (failHostCreate) {
            respondJson(exchange, 500, """{"error":"host creation failed"}""")
            return
        }
        pendingHostCreate = true
        respondJson(exchange, 200, """{"name":"create_host_1","done":false}""")
    }

    private fun handleWaitCreateHost(exchange: HttpExchange) {
        hostCreateWaitCalls += 1
        if (!pendingHostCreate) {
            respondJson(exchange, 404, """{"error":"missing host operation"}""")
            return
        }
        pendingHostCreate = false
        hostExists = true
        hostProxyReady = false
        groupsByHost.putIfAbsent("host-1", ConcurrentHashMap())
        respondJson(exchange, 200, """{"name":"host-1","docker":{"image_name":"host-orchestrator","ip_address":"127.0.0.1"}}""")
    }

    private fun handleListCvds(exchange: HttpExchange) {
        if (!hostProxyReady) {
            hostProxyReadinessChecks += 1
            hostProxyReady = true
            respondJson(exchange, 503, """{"error":"host orchestrator is still starting"}""")
            return
        }
        respondJson(exchange, 200, cvdsPayload("host-1"))
    }

    private fun handleCreateCvds(exchange: HttpExchange) {
        val body: String = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        val payload: JsonObject = json.parseToJsonElement(body) as JsonObject
        val cvd: JsonObject = payload.getValue("cvd") as JsonObject
        val buildSource: JsonObject = cvd.getValue("build_source") as JsonObject
        val systemBuildSource: JsonObject = buildSource.getValue("system_build_source") as JsonObject
        lastBuildTarget = (systemBuildSource.getValue("build_target") as JsonPrimitive).content
        val additionalInstances: Int = (payload.getValue("additional_instances_num") as JsonPrimitive).content.toInt()
        val group: String = "group_${groupsByHost["host-1"].orEmpty().size + 1}"
        val operationName = "create_cvd_${pendingGroupCreateOperations.size + 1}"
        pendingGroupCreateOperations[operationName] = group to (additionalInstances + 1)
        respondJson(exchange, 200, """{"name":"$operationName","done":false}""")
    }

    private fun handleWaitCreateCvds(exchange: HttpExchange) {
        groupCreateWaitCalls += 1
        val operationName: String = exchange.requestURI.path.substringAfterLast("/operations/").substringBefore("/:wait")
        val stored: Pair<String, Int> = pendingGroupCreateOperations.remove(operationName)
            ?: run {
                respondJson(exchange, 404, """{"error":"missing group operation"}""")
                return
            }
        val (group, count) = stored
        groupsByHost.getOrPut("host-1") { ConcurrentHashMap() }[group] = count
        if (waitPayloadMissingGroup) {
            val malformedCvdsJson: String = (1..count).joinToString(prefix = "[", postfix = "]") { index ->
                """{"name":"$group-$index","status":"Running"}"""
            }
            respondJson(exchange, 200, """{"cvds":$malformedCvdsJson}""")
            return
        }
        val cvdsJson: String = (1..count).joinToString(prefix = "[", postfix = "]") { index ->
            """{"name":"$group-$index","group":"$group","status":"Running"}"""
        }
        respondJson(exchange, 200, """{"name":"$group","cvds":$cvdsJson}""")
    }

    private fun handleDeleteGroup(exchange: HttpExchange) {
        val group: String = exchange.requestURI.path.substringAfterLast("/")
        groupsByHost["host-1"]?.remove(group)
        respondJson(exchange, 200, """{"name":"delete_group_1","done":true}""")
    }
}

private fun respondJson(exchange: HttpExchange, statusCode: Int, body: String) {
    val payload: ByteArray = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(statusCode, payload.size.toLong())
    exchange.responseBody.use { output -> output.write(payload) }
}
