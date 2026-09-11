package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.adapter.api.DEVICE_STATE_BUSY
import dev.shepherd.configureServer
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.provider.ListingProvider
import dev.shepherd.domain.provider.ListingProvider.Companion.device
import dev.shepherd.infra.state.StateStore
import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.ProviderInfoDto
import dev.shepherd.protocol.SessionResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceControlRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `devices are listed one by one and can be filtered`() = testApplication {
        startWithRack("list", ListingProvider("rack", listOf(device("A"), device("B", state = DEVICE_STATE_BUSY))))

        val all: DevicesResponse = decode(client.get("/api/v1/devices") { bearerAuth(TEST_ADMIN_TOKEN) })
        val available: DevicesResponse = decode(client.get("/api/v1/devices?state=available") { bearerAuth(TEST_ADMIN_TOKEN) })
        val one = client.get("/api/v1/devices/rack:A") { bearerAuth(TEST_ADMIN_TOKEN) }
        val missing = client.get("/api/v1/devices/rack:Z") { bearerAuth(TEST_ADMIN_TOKEN) }

        assertEquals(setOf("rack:A", "rack:B"), all.devices.map { it.id }.toSet())
        assertEquals(listOf("rack:A"), available.devices.map { it.id })
        assertEquals(HttpStatusCode.OK, one.status)
        assertContains(one.bodyAsText(), "\"localId\": \"A\"")
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `maintenance keeps a device out of allocation until it is cleared`() = testApplication {
        val services = startWithRack("maintenance", ListingProvider("rack", listOf(device("A"))))
        val user = services.issueKey("ci")

        val userAttempt = client.put("/api/v1/devices/rack:A/maintenance") { bearerAuth(user) }
        val enter = client.put("/api/v1/devices/rack:A/maintenance") {
            bearerAuth(TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"cracked screen"}""")
        }
        val refused = requestPhysical(user)
        val leave = client.delete("/api/v1/devices/rack:A/maintenance") { bearerAuth(TEST_ADMIN_TOKEN) }
        val allowed = requestPhysical(user)

        assertEquals(HttpStatusCode.Forbidden, userAttempt.status)
        assertContains(enter.bodyAsText(), "\"state\": \"maintenance\"")
        assertContains(enter.bodyAsText(), "cracked screen")
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        assertEquals(HttpStatusCode.OK, leave.status)
        assertEquals(HttpStatusCode.Created, allowed.status)
    }

    @Test
    fun `a session pinned to a device reports it, and the device reports its holder`() = testApplication {
        startWithRack("pinned", ListingProvider("rack", listOf(device("A"), device("B"))))

        val created: SessionResponse = decode(
            client.post("/api/v1/sessions") {
                bearerAuth(TEST_ADMIN_TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"deviceIds":["rack:B"],"ttlSeconds":600,"name":"nightly #42"}""")
            }
        )
        val deviceBody = client.get("/api/v1/devices/rack:B") { bearerAuth(TEST_ADMIN_TOKEN) }.bodyAsText()

        assertEquals("READY", created.status)
        assertEquals(1, created.requestedDevices, "without maxDevices a pinned request asks for every named device")
        assertEquals(listOf("rack:B"), created.devices.map { it.id })
        assertEquals("nightly #42", created.name)
        assertContains(deviceBody, "\"sessionId\": \"${created.id}\"")
        assertContains(deviceBody, "\"state\": \"busy\"")
    }

    @Test
    fun `heartbeat and extend work over http`() = testApplication {
        startWithRack("extend", ListingProvider("rack", listOf(device("A"))))
        val created: SessionResponse = decode(requestPhysical(TEST_ADMIN_TOKEN))

        val heartbeat = client.post("/api/v1/sessions/${created.id}/heartbeat") { bearerAuth(TEST_ADMIN_TOKEN) }
        val extended: SessionResponse = decode(
            client.post("/api/v1/sessions/${created.id}/extend") {
                bearerAuth(TEST_ADMIN_TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"ttlSeconds":7200}""")
            }
        )

        assertEquals(HttpStatusCode.OK, heartbeat.status)
        assertTrue(Instant.parse(extended.expiresAt).isAfter(Instant.parse(created.expiresAt)))
    }

    @Test
    fun `extend answers 409 when a provider cannot renew leases`() = testApplication {
        startManager(tempDir, "no-renew")
        val created = createSession(TEST_ADMIN_TOKEN).session()

        val response = client.post("/api/v1/sessions/${created.id}/extend") {
            bearerAuth(TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"ttlSeconds":7200}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertContains(response.bodyAsText(), "cannot extend leases")
    }

    @Test
    fun `adapters register with a provider key and appear in the provider list`() = testApplication {
        val services = startManager(tempDir, "register")
        val agent = services.issueKey("rack-7-agent", Role.PROVIDER)
        val user = services.issueKey("ci", Role.USER)

        val registered = register(agent, "rack-7")
        val byUser = register(user, "rack-8")
        val listedBody = client.get("/api/v1/providers") { bearerAuth(TEST_ADMIN_TOKEN) }.bodyAsText()
        val listed: List<ProviderInfoDto> = TestJson.decodeFromString(listedBody)
        val removed = client.delete("/api/v1/providers/rack-7") { bearerAuth(TEST_ADMIN_TOKEN) }
        val afterRemoval: List<ProviderInfoDto> = decode(client.get("/api/v1/providers") { bearerAuth(TEST_ADMIN_TOKEN) })

        assertEquals(HttpStatusCode.OK, registered.status)
        assertContains(registered.bodyAsText(), "heartbeatIntervalSeconds")
        assertEquals(HttpStatusCode.Forbidden, byUser.status)
        val entry = listed.single { it.name == "rack-7" }
        assertEquals("registered", entry.source)
        assertTrue(entry.active)
        assertEquals("rack-7-agent", entry.registeredBy)
        assertEquals("static", listed.single { it.name == "route-provider" }.source)
        assertFalse(listedBody.contains("s3cret"), "adapter secrets must never be listed")
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(afterRemoval.none { it.name == "rack-7" })
    }

    @Test
    fun `the event stream replays after Last-Event-ID and then goes live`() = testApplication {
        val services = startManager(tempDir, "events")
        services.eventBus.publish("test.before", buildJsonObject { put("n", 1) })

        client.prepareGet("/api/v1/events?types=test.") {
            bearerAuth(TEST_ADMIN_TOKEN)
            header("Last-Event-ID", "0")
        }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertContains(response.headers[HttpHeaders.ContentType].orEmpty(), "text/event-stream")
            val stream = response.bodyAsChannel()
            val replayed = withTimeout(10_000) { stream.nextData() }
            services.eventBus.publish("other.skipped", buildJsonObject { put("n", 2) })
            services.eventBus.publish("test.after", buildJsonObject { put("n", 3) })
            val live = withTimeout(10_000) { stream.nextData() }

            assertContains(replayed, "\"type\":\"test.before\"")
            assertContains(live, "\"type\":\"test.after\"")
        }
    }

    @Test
    fun `the event stream needs a key with a reader role`() = testApplication {
        val services = startManager(tempDir, "events-auth")
        val agent = services.issueKey("agent", Role.PROVIDER)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/events").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/events") { bearerAuth(agent) }.status)
    }

    private fun ApplicationTestBuilder.startWithRack(name: String, rack: ListingProvider): ManagerServices {
        val configFile = File(tempDir, "$name.yaml")
        configFile.writeText("providers:\n  - name: \"${rack.name}\"\n    url: \"http://127.0.0.1:7037\"\n")
        val services = managerServices(
            createRouteProviderRegistry(configFile, listOf(rack)),
            StateStore(File(tempDir, "$name.db").absolutePath)
        )
        application { configureServer(services) }
        return services
    }

    private suspend fun ApplicationTestBuilder.requestPhysical(key: String): HttpResponse = client.post("/api/v1/sessions") {
        bearerAuth(key)
        contentType(ContentType.Application.Json)
        setBody("""{"maxDevices":1,"api":"34","deviceType":"physical","ttlSeconds":600}""")
    }

    private suspend fun ApplicationTestBuilder.register(key: String, name: String): HttpResponse =
        client.post("/api/v1/providers/register") {
            bearerAuth(key)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","url":"http://10.0.0.7:7037","secret":"s3cret"}""")
        }

    private suspend inline fun <reified T> decode(response: HttpResponse): T = TestJson.decodeFromString(response.bodyAsText())

    private suspend fun ByteReadChannel.nextData(): String {
        while (true) {
            val line: String = readLine() ?: error("event stream closed")
            if (line.startsWith("data: ")) {
                return line.removePrefix("data: ")
            }
        }
    }
}
