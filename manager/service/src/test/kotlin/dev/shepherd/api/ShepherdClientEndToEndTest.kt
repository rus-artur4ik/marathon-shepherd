package dev.shepherd.api

import dev.shepherd.client.ShepherdClient
import dev.shepherd.protocol.CreateClientRequest
import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.DeviceQuery
import dev.shepherd.protocol.QuotaDto
import dev.shepherd.protocol.ShepherdApiException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Kotlin client against the real routes: proves both sides agree on the wire format. */
class ShepherdClientEndToEndTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the Kotlin client drives a real manager`() = testApplication {
        startManager(tempDir, "client-e2e")
        val http = createClient { install(HttpTimeout) }
        val admin = ShepherdClient(baseUrl = "", token = TEST_ADMIN_TOKEN, httpClient = http)

        val issued = admin.createClient(CreateClientRequest(name = "ci", quota = QuotaDto(maxDevices = 2)))
        val ci = ShepherdClient(baseUrl = "", token = issued.apiKey, httpClient = http)
        val me = ci.whoAmI()
        val devices = ci.listDevices(DeviceQuery(refresh = true))
        val session = ci.acquire(CreateSessionRequest(maxDevices = 1, api = "34", deviceType = "emulator", ttlSeconds = 600, name = "e2e"))
        val mine = ci.listSessions(owner = "me")
        val cannotExtend = assertFailsWith<ShepherdApiException> { ci.extendSession(session.id, 1_200) }
        ci.releaseSession(session.id)
        val missing = assertFailsWith<ShepherdApiException> { ci.getSession("sess_missing") }
        val forbidden = assertFailsWith<ShepherdApiException> { ci.listClients() }
        val unauthorized = assertFailsWith<ShepherdApiException> { ShepherdClient("", "msh_wrong", http).whoAmI() }
        val health = admin.health()
        val audit = admin.audit(action = "session.")

        assertEquals("ci", me.name)
        assertEquals(2, me.quota.maxDevices)
        assertEquals(1, devices.providers.size)
        assertEquals("READY", session.status)
        assertEquals("e2e", session.name)
        assertEquals(listOf(session.id), mine.map { it.id })
        assertEquals(409, cannotExtend.status, "the test provider cannot renew leases")
        assertEquals(404, missing.status)
        assertEquals(403, forbidden.status)
        assertEquals(401, unauthorized.status)
        assertEquals("healthy", health.status)
        assertTrue(audit.entries.any { it.action == "session.release" && it.actor == "ci" })
    }
}
