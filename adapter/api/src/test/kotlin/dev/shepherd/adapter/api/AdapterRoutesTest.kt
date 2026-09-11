package dev.shepherd.adapter.api

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AdapterRoutesTest {

    @Test
    fun `lease renew and lease listing answer 501 when the adapter does not support them`() = testApplication {
        application { configureAdapterApplication(PoolOnlyHandler(), testEnv()) }

        val renew = client.renew("lease-1", ttlSeconds = 60)
        val leases = client.get("/leases") { bearerAuth(SECRET) }

        assertEquals(HttpStatusCode.NotImplemented, renew.status)
        assertEquals("The pool-only adapter does not support lease renewal", renew.field("error"))
        assertEquals(HttpStatusCode.NotImplemented, leases.status)
        assertEquals("The pool-only adapter does not support listing leases", leases.field("error"))
    }

    @Test
    fun `renew answers 200 for a known lease and 404 for an unknown one`() = testApplication {
        val handler = LeaseAwareHandler(knownLeases = setOf("lease-1"))
        application { configureAdapterApplication(handler, testEnv()) }

        val known = client.renew("lease-1", ttlSeconds = 90)
        val unknown = client.renew("lease-2", ttlSeconds = 90)

        assertEquals(HttpStatusCode.OK, known.status)
        assertEquals("renewed", known.field("status"))
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("Unknown lease lease-2", unknown.field("error"))
        assertEquals(listOf("lease-1" to 90L, "lease-2" to 90L), handler.renewals)
    }

    @Test
    fun `renew rejects a ttl that is not positive without asking the adapter`() = testApplication {
        val handler = LeaseAwareHandler(knownLeases = setOf("lease-1"))
        application { configureAdapterApplication(handler, testEnv()) }

        assertEquals(HttpStatusCode.BadRequest, client.renew("lease-1", ttlSeconds = 0).status)
        assertEquals(HttpStatusCode.BadRequest, client.renew("lease-1", ttlSeconds = -5).status)
        assertEquals(emptyList(), handler.renewals)
    }

    @Test
    fun `lease listing returns the leases the adapter holds`() = testApplication {
        application { configureAdapterApplication(LeaseAwareHandler(knownLeases = setOf("lease-1")), testEnv()) }

        val response = client.get("/leases") { bearerAuth(SECRET) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            LeasesResponse(leases = listOf(AdapterLease(leaseId = "lease-1", deviceIds = listOf("serial-1"), sessionId = "session-1"))),
            json.decodeFromString<LeasesResponse>(response.bodyAsText())
        )
    }

    @Test
    fun `lease routes require the adapter secret`() = testApplication {
        application { configureAdapterApplication(LeaseAwareHandler(knownLeases = setOf("lease-1")), testEnv()) }

        val renew = client.post("/leases/lease-1/renew") {
            contentType(ContentType.Application.Json)
            setBody("""{"ttlSeconds":60}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, renew.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/leases").status)
    }

    @Test
    fun `status passes the adapter's devices through`() = testApplication {
        application { configureAdapterApplication(LeaseAwareHandler(knownLeases = emptySet()), testEnv()) }

        val response = client.get("/status") { bearerAuth(SECRET) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(STATUS_DEVICES, json.decodeFromString<PoolStatusResponse>(response.bodyAsText()).devices)
    }

    @Test
    fun `acquire passes the leased devices through`() = testApplication {
        application { configureAdapterApplication(LeaseAwareHandler(knownLeases = emptySet()), testEnv()) }

        val response = client.acquire("""{"count":1,"apiLevel":"34","ttlSeconds":60}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            listOf(AdapterLeasedDevice(id = "serial-1", connectionId = "fake-serial-1")),
            json.decodeFromString<AcquireResponse>(response.bodyAsText()).devices
        )
    }

    @Test
    fun `acquire accepts fields a newer manager adds`() = testApplication {
        val handler = LeaseAwareHandler(knownLeases = emptySet())
        application { configureAdapterApplication(handler, testEnv()) }

        val response = client.acquire(
            """{"count":1,"apiLevel":"34","ttlSeconds":60,"sessionId":"session-7","priority":"high","hints":{"x":1}}"""
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("session-7", handler.lastAcquire?.sessionId)
    }

    private suspend fun HttpClient.renew(leaseId: String, ttlSeconds: Long): HttpResponse = post("/leases/$leaseId/renew") {
        bearerAuth(SECRET)
        contentType(ContentType.Application.Json)
        setBody("""{"ttlSeconds":$ttlSeconds}""")
    }

    private suspend fun HttpClient.acquire(body: String): HttpResponse = post("/acquire") {
        bearerAuth(SECRET)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.field(name: String): String? =
        json.parseToJsonElement(bodyAsText()).jsonObject[name]?.jsonPrimitive?.content

    private fun testEnv() = AdapterEnv(
        port = 0,
        advertisedAdbPort = 5037,
        secret = SECRET,
        accessMode = ACCESS_EXPOSURE_DIRECT_TCP,
        accessAuthType = ACCESS_AUTH_NETWORK,
        accessScope = "test"
    )

    private companion object {
        const val SECRET = "s3cret"
        val json = Json { ignoreUnknownKeys = true }
    }
}

private val STATUS_DEVICES: List<AdapterDevice> = listOf(
    AdapterDevice(
        id = "serial-1",
        deviceType = DEVICE_TYPE_PHYSICAL,
        state = DEVICE_STATE_BUSY,
        apiLevel = "34",
        leaseId = "lease-1",
        labels = mapOf("form" to "phone")
    ),
    AdapterDevice(
        id = "serial-2",
        deviceType = DEVICE_TYPE_PHYSICAL,
        state = DEVICE_STATE_OFFLINE,
        metadata = mapOf("reason" to "unauthorized")
    )
)

/** Declares none of the lease features, like the farm adapter. */
private class PoolOnlyHandler : AdapterHandler(adapterType = "pool-only") {
    override suspend fun isHealthy(): Boolean = true

    override suspend fun status(): AdapterStatus = AdapterStatus(pool = AdapterPool(available = 1, busy = 0, total = 1))

    override suspend fun acquire(request: AcquireRequest): AcquireResult = AcquireResult(leaseId = "lease-1", acquiredCount = request.count)

    override suspend fun release(leaseId: String): Boolean = true
}

private class LeaseAwareHandler(private val knownLeases: Set<String>) : AdapterHandler(adapterType = "fake") {
    val renewals: MutableList<Pair<String, Long>> = mutableListOf()
    var lastAcquire: AcquireRequest? = null
        private set

    override suspend fun isHealthy(): Boolean = true

    override suspend fun status(): AdapterStatus =
        AdapterStatus(pool = AdapterPool(available = 0, busy = 1, total = 1), devices = STATUS_DEVICES)

    override suspend fun acquire(request: AcquireRequest): AcquireResult {
        lastAcquire = request
        return AcquireResult(
            leaseId = "lease-1",
            acquiredCount = 1,
            devices = listOf(AdapterLeasedDevice(id = "serial-1", connectionId = "fake-serial-1"))
        )
    }

    override suspend fun release(leaseId: String): Boolean = true

    override suspend fun renew(leaseId: String, ttlSeconds: Long): Boolean {
        renewals += leaseId to ttlSeconds
        return leaseId in knownLeases
    }

    override suspend fun leases(): List<AdapterLease> =
        knownLeases.map { leaseId -> AdapterLease(leaseId = leaseId, deviceIds = listOf("serial-1"), sessionId = "session-1") }
}
