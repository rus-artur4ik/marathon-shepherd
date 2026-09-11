package dev.shepherd.adapter.cuttlefish

import com.sun.net.httpserver.HttpServer
import dev.shepherd.adapter.api.*
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CuttlefishAdapterHandlerTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `status lists running instances as devices, busy while their group is leased`() = runBlocking {
        val handler = handler(FakeLegacyCloudOrchestratorBackend(initialInstances = 2))
        val leaseId = requireNotNull(handler.acquire(AcquireRequest(count = 1, apiLevel = "34", ttlSeconds = 600)).leaseId)

        val status = handler.status()

        assertEquals(AdapterPool(available = 2, busy = 1, total = 3), status.pool)
        assertEquals(
            listOf(
                instance(id = "bootstrap.bootstrap-1", group = "bootstrap"),
                instance(id = "bootstrap.bootstrap-2", group = "bootstrap"),
                instance(id = "group_2.group_2-1", group = "group_2", leaseId = leaseId)
            ),
            status.devices
        )
    }

    @Test
    fun `status lists the orchestrator's instances once`() = runBlocking {
        val backend = FakeLegacyCloudOrchestratorBackend(initialInstances = 2)
        val handler = handler(backend)
        assertTrue(handler.isHealthy())
        val listingsBefore: Int = backend.deviceListings

        handler.status()

        assertEquals(1, backend.deviceListings - listingsBefore)
    }

    @Test
    fun `device ids join group and name and replace unsafe characters`() {
        assertEquals("cvd_1.1", cuttlefishDeviceId(group = "cvd_1", name = "1"))
        assertEquals("cvd-2.phone-a", cuttlefishDeviceId(group = "cvd 2", name = "phone/a"))
        assertEquals("solo", cuttlefishDeviceId(group = null, name = "solo"))
    }

    @Test
    fun `renew accepts only leases the adapter holds`() = runBlocking {
        val handler = handler(FakeLegacyCloudOrchestratorBackend(initialInstances = 0))
        val leaseId = requireNotNull(handler.acquire(AcquireRequest(count = 1, apiLevel = "34", ttlSeconds = 600)).leaseId)

        assertEquals(true, handler.renew(leaseId, ttlSeconds = 600))
        assertEquals(false, handler.renew("cf_unknown", ttlSeconds = 600))
    }

    @Test
    fun `leases lists active leases with the session they were acquired for`() = runBlocking {
        val handler = handler(FakeLegacyCloudOrchestratorBackend(initialInstances = 0))
        val leaseId = requireNotNull(
            handler.acquire(AcquireRequest(count = 2, apiLevel = "34", ttlSeconds = 600, sessionId = "session-1")).leaseId
        )

        assertEquals(listOf(AdapterLease(leaseId = leaseId, sessionId = "session-1")), handler.leases())

        handler.release(leaseId)

        assertEquals(emptyList(), handler.leases())
    }

    @Test
    fun `capabilities declare lease renew and lease list but not device selection`() {
        val features: List<String> = handler(FakeLegacyCloudOrchestratorBackend(initialInstances = 0)).capabilities(testEnv()).features

        assertTrue(FEATURE_LEASE_RENEW in features, features.toString())
        assertTrue(FEATURE_LEASE_LIST in features, features.toString())
        assertFalse(FEATURE_DEVICE_SELECTION in features, features.toString())
    }

    private fun handler(backend: FakeLegacyCloudOrchestratorBackend): CuttlefishAdapterHandler {
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/") { exchange -> backend.handle(exchange) }
        httpServer.start()
        server = httpServer
        return CuttlefishAdapterHandler(
            CloudOrchestratorService(
                orchestratorUrl = "http://127.0.0.1:${httpServer.address.port}",
                httpClient = buildCloudOrchestratorHttpClient()
            )
        )
    }

    private fun instance(id: String, group: String, leaseId: String? = null) = AdapterDevice(
        id = id,
        deviceType = DEVICE_TYPE_EMULATOR,
        state = if (leaseId == null) DEVICE_STATE_AVAILABLE else DEVICE_STATE_BUSY,
        leaseId = leaseId,
        metadata = mapOf("group" to group)
    )

    private fun testEnv() = AdapterEnv(
        port = 7037,
        advertisedAdbPort = 6520,
        secret = "secret",
        accessMode = ACCESS_EXPOSURE_DIRECT_TCP,
        accessAuthType = ACCESS_AUTH_NETWORK,
        accessScope = "private-network"
    )
}
