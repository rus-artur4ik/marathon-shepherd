package dev.shepherd.client

import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.DeviceQuery
import dev.shepherd.protocol.ShepherdApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class ShepherdClientTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    @Test
    fun `calls carry the key and decode the answer`() = runTest {
        val client = client { respondJson(SESSION_READY) }

        val session = client.getSession("sess_1")

        assertEquals("READY", session.status)
        assertEquals("Bearer msh_key", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `refusals become exceptions with the server's message`() = runTest {
        val client = client { respondJson("""{"error":"Device quota exhausted"}""", HttpStatusCode.TooManyRequests) }

        val error = assertFailsWith<ShepherdApiException> { client.createSession(CreateSessionRequest(maxDevices = 1)) }

        assertEquals(429, error.status)
        assertEquals("Device quota exhausted", error.message)
    }

    @Test
    fun `request bodies leave unset fields out and device ids are path segments`() = runTest {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/api/v1/sessions" -> respondJson(SESSION_READY)
                "/api/v1/devices" -> respondJson("""{"providers":[],"totalAvailable":0,"totalBusy":0}""")
                else -> respondJson(DEVICE)
            }
        }

        client.createSession(CreateSessionRequest(maxDevices = 2, api = ">=34"))
        client.getDevice("rack-1:192.168.1.5:5555")
        client.listDevices(DeviceQuery(state = "available", labels = mapOf("form" to "tablet")))

        assertFalse(bodies.first().contains("deviceType"), bodies.first())
        assertFalse(bodies.first().contains("null"), bodies.first())
        assertEquals("/api/v1/devices/rack-1:192.168.1.5:5555", requests[1].url.encodedPath.replace("%3A", ":"))
        assertEquals("form=tablet", requests[2].url.parameters["label"])
    }

    @Test
    fun `acquire waits through the queue until the session is ready`() = runTest {
        var waits = 0
        val client = client { request ->
            when {
                request.url.encodedPath == "/api/v1/sessions" -> respondJson(SESSION_PENDING, HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/wait") -> respondJson(if (++waits < 3) SESSION_PENDING else SESSION_READY)
                else -> respondJson("""{"status":"released"}""")
            }
        }
        val updates = mutableListOf<String>()

        val session = client.acquire(CreateSessionRequest(maxDevices = 1)) { update -> updates += update.status }

        assertEquals("READY", session.status)
        assertEquals(listOf("PENDING", "PENDING", "PENDING", "READY"), updates)
        assertFalse(requests.any { it.method == HttpMethod.Delete })
    }

    @Test
    fun `acquire releases a session that is still queued when it gives up`() = runBlocking {
        val client = client { request ->
            if (request.method == HttpMethod.Delete) respondJson("""{"status":"released"}""") else respondJson(SESSION_PENDING)
        }

        assertFailsWith<QueueTimeoutException> { client.acquire(CreateSessionRequest(maxDevices = 1), queueTimeout = 200.milliseconds) }

        assertEquals("/api/v1/sessions/sess_1", requests.last { it.method == HttpMethod.Delete }.url.encodedPath)
    }

    @Test
    fun `withSession always releases, even when the block fails`() = runTest {
        val client = client { request ->
            if (request.method == HttpMethod.Delete) respondJson("""{"status":"released"}""") else respondJson(SESSION_READY)
        }

        assertFailsWith<IllegalStateException> {
            client.withSession(CreateSessionRequest(maxDevices = 1)) { error("tests crashed") }
        }

        assertEquals(1, requests.count { it.method == HttpMethod.Delete })
    }

    @Test
    fun `the event stream reconnects and resumes after the last event`() = runTest {
        var connections = 0
        val client = client { request ->
            connections += 1
            val body = if (connections == 1) {
                ": keep-alive\n\nid: 1\nevent: session.created\ndata: {\"id\":1,\"type\":\"session.created\",\"at\":\"t\",\"data\":{}}\n\n"
            } else {
                "id: 2\nevent: session.ready\ndata: {\"id\":2,\"type\":\"session.ready\",\"at\":\"t\",\"data\":{}}\n\n"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        val events = client.events(types = listOf("session."), reconnectDelay = 1.milliseconds).take(2).toList()

        assertEquals(listOf("session.created", "session.ready"), events.map { it.type })
        assertEquals("1", requests[1].headers["Last-Event-ID"])
        assertContains(requests[0].url.parameters["types"].orEmpty(), "session.")
    }

    @Test
    fun `the event stream stops on authentication errors`() = runTest {
        val client = client { respondJson("""{"error":"Missing or invalid API key"}""", HttpStatusCode.Unauthorized) }

        val error = assertFailsWith<ShepherdApiException> { client.events().toList() }

        assertEquals(401, error.status)
    }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): ShepherdClient {
        val engine = MockEngine { request ->
            requests += request
            (request.body as? TextContent)?.let { content -> bodies += content.text }
            handler(request)
        }
        return ShepherdClient(baseUrl = "http://manager:6037", token = "msh_key", httpClient = HttpClient(engine))
    }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        fun session(status: String, servers: String): String =
            """{"id":"sess_1","status":"$status","requestedDevices":1,"allocatedDevices":1,""" +
                """"adbServers":[$servers],"createdAt":"t","expiresAt":"t"}"""

        val SESSION_PENDING = session("PENDING", servers = "")
        val SESSION_READY = session("READY", servers = """{"host":"h","port":7600}""")
        const val DEVICE =
            """{"id":"rack-1:192.168.1.5:5555","provider":"rack-1","localId":"192.168.1.5:5555",""" +
                """"deviceType":"physical","state":"available"}"""
    }
}
