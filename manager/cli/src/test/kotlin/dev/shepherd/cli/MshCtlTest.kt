package dev.shepherd.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.testing.test
import dev.shepherd.client.ShepherdClient
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MshCtlTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    @Test
    fun `create sends the options it was given and prints the session`() {
        val result = cli { respondJson(SESSION_READY, HttpStatusCode.Created) }
            .test("create --devices 2 --api 34 --label form=tablet --meta build=42 --name nightly --token msh_key")

        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "Session sess_1 (READY)")
        assertContains(result.stdout, "ADB servers: h:7600")
        val body: JsonObject = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("2", body["maxDevices"]?.jsonPrimitive?.content)
        assertEquals("tablet", body["labels"]?.jsonObject?.get("form")?.jsonPrimitive?.content)
        assertEquals("42", body["metadata"]?.jsonObject?.get("build")?.jsonPrimitive?.content)
        assertEquals("nightly", body["name"]?.jsonPrimitive?.content)
        assertFalse("deviceType" in body, bodies.single())
        assertEquals("Bearer msh_key", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `json output is the session object`() {
        val result = cli { respondJson(SESSION_READY, HttpStatusCode.Created) }
            .test("create --json --devices 1 --api 34 --device-type emulator --ttl 120")

        assertEquals("sess_1", Json.parseToJsonElement(result.stdout).jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create --wait stays queued until the devices are allocated`() {
        val result = cli { request ->
            if (request.url.encodedPath.endsWith(
                    "/wait"
                )
            ) {
                respondJson(SESSION_READY)
            } else {
                respondJson(SESSION_PENDING, HttpStatusCode.Created)
            }
        }.test("create --wait")

        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stderr, "Session sess_1 is queued")
        assertContains(result.stdout, "Session sess_1 (READY)")
    }

    @Test
    fun `sessions are named by argument or by --id`() {
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
            if (request.method == HttpMethod.Delete) respondJson("""{"status":"released"}""") else respondJson(SESSION_READY)
        }

        val shown = cli(handler).test("show sess_1")
        val released = cli(handler).test("release --id sess_1")
        val missing = cli(handler).test("show")

        assertEquals(0, shown.statusCode, shown.stderr)
        assertContains(released.stdout, "Session sess_1 released.")
        assertEquals(listOf("/api/v1/sessions/sess_1", "/api/v1/sessions/sess_1"), requests.map { request -> request.url.encodedPath })
        assertEquals(1, missing.statusCode)
        assertContains(missing.stderr, "Missing session id")
    }

    @Test
    fun `a rejected key explains how to pass one`() {
        val result = cli { respondJson("""{"error":"Missing or invalid API key"}""", HttpStatusCode.Unauthorized) }.test("list")

        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "Missing or invalid API key. Set MSH_TOKEN or pass --token.")
    }

    @Test
    fun `the manager URL and key come from the environment`() {
        val result = cli { respondJson("[]") }
            .test("list --mine", envvars = mapOf("MSH_URL" to "http://manager.example:7000", "MSH_TOKEN" to "msh_env"))

        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "No active sessions.")
        assertEquals("manager.example", requests.single().url.host)
        assertEquals("me", requests.single().url.parameters["owner"])
        assertEquals("Bearer msh_env", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `health fails when no provider is healthy`() {
        val result = cli { respondJson(UNHEALTHY, HttpStatusCode.ServiceUnavailable) }.test("health")

        assertEquals(1, result.statusCode)
        assertContains(result.stdout, "Health: unhealthy")
        assertContains(result.stdout, "- rack-1: unhealthy | connection refused")
    }

    @Test
    fun `devices lists individual devices`() {
        val result = cli { respondJson(DEVICES) }.test("devices --state available --label form=phone")

        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "rack-1:serial-1  available  physical  34   Google Pixel 8")
        assertContains(result.stdout, "Totals: available 1, busy 0")
        assertEquals("available", requests.single().url.parameters["state"])
        assertEquals("form=phone", requests.single().url.parameters["label"])
    }

    @Test
    fun `clients update keeps the quota limits it was not asked to change`() {
        val result = cli { respondJson(CLIENT) }.test("clients update cl_1 --max-devices none --max-lifetime 3600")

        assertEquals(0, result.statusCode, result.stderr)
        val patch: HttpRequestData = requests.single { request -> request.method == HttpMethod.Patch }
        assertEquals("/api/v1/admin/clients/cl_1", patch.url.encodedPath)
        val quota: JsonObject = Json.parseToJsonElement(bodies.single()).jsonObject.getValue("quota").jsonObject
        assertFalse("maxDevices" in quota, bodies.single())
        assertEquals("3600", quota["maxSessionLifetimeSeconds"]?.jsonPrimitive?.content)
        assertEquals("5", quota["maxPriority"]?.jsonPrimitive?.content)
    }

    @Test
    fun `clients update refuses to send an empty change`() {
        val result = cli { respondJson(CLIENT) }.test("clients update cl_1")

        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "Nothing to change")
        assertEquals(emptyList(), requests)
    }

    private fun cli(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): CliktCommand {
        val engine = MockEngine { request ->
            requests += request
            (request.body as? TextContent)?.let { content -> bodies += content.text }
            handler(request)
        }
        return mshctl { managerUrl, token -> ShepherdClient(managerUrl, token, HttpClient(engine)) }
    }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        fun session(status: String, servers: String): String =
            """{"id":"sess_1","status":"$status","requestedDevices":1,"allocatedDevices":1,""" +
                """"adbServers":[$servers],"createdAt":"t","expiresAt":"t"}"""

        val SESSION_PENDING = session("PENDING", servers = "")
        val SESSION_READY = session("READY", servers = """{"host":"h","port":7600}""")
        const val UNHEALTHY =
            """{"status":"unhealthy","version":"0.2.0","providersTotal":1,"providersHealthy":0,""" +
                """"providers":[{"name":"rack-1","status":"unhealthy","error":"connection refused"}]}"""
        const val DEVICES =
            """{"providers":[],"totalAvailable":1,"totalBusy":0,"devices":[{"id":"rack-1:serial-1","provider":"rack-1",""" +
                """"localId":"serial-1","deviceType":"physical","state":"available","apiLevel":"34","manufacturer":"Google",""" +
                """"model":"Pixel 8","labels":{"form":"phone"}}]}"""
        const val CLIENT =
            """{"id":"cl_1","name":"ci","role":"user","keyPrefix":"msh_ab12","quota":{"maxDevices":2,"maxPriority":5},""" +
                """"effectiveQuota":{"maxDevices":2,"maxPriority":5},"active":true,"createdAt":"t"}"""
    }
}
