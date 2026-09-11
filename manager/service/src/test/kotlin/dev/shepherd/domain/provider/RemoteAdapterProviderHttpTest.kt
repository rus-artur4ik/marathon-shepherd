package dev.shepherd.domain.provider

import dev.shepherd.domain.model.AdbServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// runBlocking, not runTest: the provider bounds every call with withTimeout, and runTest's
// virtual clock would fire those timeouts while MockEngine answers on a real thread.
class RemoteAdapterProviderHttpTest {

    private val acquireBodies = mutableListOf<String>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val engine = MockEngine { request ->
        when (request.url.encodedPath) {
            "/status" -> respond(STATUS, headers = json)
            "/acquire" -> {
                acquireBodies += (request.body as TextContent).text
                respond(ACQUIRE, headers = json)
            }
            "/leases/lease-1/renew" -> respond("""{"status":"renewed"}""", headers = json)
            "/leases/lease-9/renew" -> respond("""{"error":"unknown lease"}""", HttpStatusCode.NotFound, json)
            "/leases" -> respond("""{"leases":[{"leaseId":"lease-1","deviceIds":["A"]}]}""", headers = json)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private val provider = RemoteAdapterProvider(
        name = "rack",
        adapterUrl = "http://adapter.internal:7037",
        accessHost = "devices.internal",
        secret = "s3cret",
        httpClient = HttpClient(engine)
    )

    @Test
    fun `status caches the adapter's device list`() = runBlocking {
        provider.queryDevices()

        assertEquals(listOf("A"), provider.devices.map { it.id })
        assertTrue(provider.supportsFeature("device-selection"))
    }

    @Test
    fun `selection fields reach the adapter only when set`() = runBlocking {
        provider.acquire(1, "34", 60)
        val targeted = provider.acquire(1, "34", 60, DeviceSelection(deviceIds = listOf("A"), sessionId = "sess_1"))

        assertFalse(acquireBodies[0].contains("deviceIds"), "older adapters reject unknown fields, so empty ones stay out")
        assertFalse(acquireBodies[0].contains("sessionId"))
        assertTrue(acquireBodies[1].contains("\"deviceIds\":[\"A\"]"), acquireBodies[1])
        assertEquals(AdbServer("devices.internal", 7600), targeted.devices.single().adbServer)
    }

    @Test
    fun `renew and lease listing follow the adapter's answers`() = runBlocking {
        assertTrue(provider.renew("lease-1", 600))
        assertFalse(provider.renew("lease-9", 600))
        assertEquals(listOf("lease-1"), provider.leases()?.map { it.leaseId })
    }

    private companion object {
        const val STATUS = """
            {"pool":{"available":1,"busy":0,"total":1},
             "access":{"connections":[]},
             "capabilities":{"features":["device-selection","lease-renew","lease-list"]},
             "devices":[{"id":"A","deviceType":"physical","state":"available","apiLevel":"34"}]}
        """
        const val ACQUIRE = """
            {"leaseId":"lease-1","acquiredCount":1,
             "access":{"preferredConnectionId":"adb-A","connections":[
               {"id":"adb-A","protocol":"adb","transport":"tcp","host":"adapter.internal","port":7600,"exposure":"direct-tcp"}]},
             "devices":[{"id":"A","connectionId":"adb-A"}]}
        """
    }
}
