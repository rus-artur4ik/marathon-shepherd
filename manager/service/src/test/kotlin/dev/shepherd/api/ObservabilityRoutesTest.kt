package dev.shepherd.api

import dev.shepherd.configureServer
import dev.shepherd.domain.SessionManager
import dev.shepherd.infra.metrics.MicrometerManagerMetrics
import dev.shepherd.infra.state.StateStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservabilityRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `metrics endpoint exposes domain, http and jvm metrics`() = testApplication {
        val stateStore = StateStore(File(tempDir, "metrics.db").absolutePath)
        val providerRegistry = createRouteProviderRegistry(tempDir, "metrics.yaml", listOf(RouteTestProvider(availableDevices = 1)))
        val metrics = MicrometerManagerMetrics()
        val sessionManager = SessionManager(providerRegistry, stateStore, metrics = metrics)
        application { configureServer(managerServices(providerRegistry, stateStore, sessionManager, metrics)) }

        val created = client.post("/api/v1/sessions") {
            bearerAuth(TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"maxDevices":1,"api":"34","deviceType":"emulator","ttlSeconds":120}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        // /health runs a fleet poll, which feeds the provider and session gauges.
        client.get("/health")

        val response = client.get("/metrics")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers[HttpHeaders.ContentType].orEmpty(), "text/plain")
        assertMetric(body, """msh_build_info\{.*component="manager".*\} 1(\.0)?""")
        assertMetric(body, """msh_sessions_accepted_total\{device_type="emulator"\} 1(\.0)?""")
        assertMetric(body, """msh_sessions_queue_wait_seconds_count 1(\.0)?""")
        assertMetric(body, """msh_sessions\{status="ready"\} 1(\.0)?""")
        assertMetric(body, """msh_sessions\{status="pending"\} 0(\.0)?""")
        assertMetric(body, """msh_provider_up\{provider="route-provider"\} 1(\.0)?""")
        assertMetric(body, """msh_provider_devices\{provider="route-provider",state="total"\} 1(\.0)?""")
        assertMetric(body, """msh_fleet_last_poll_seconds [1-9]""")
        assertMetric(body, """ktor_http_server_requests_seconds_count\{""")
        assertContains(body, "jvm_memory_used_bytes")
    }

    @Test
    fun `readiness reports the database check`() = testApplication {
        configureManager("ready")

        val response = client.get("/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "\"database\": \"ok\"")
    }

    @Test
    fun `openapi document and swagger ui are served`() = testApplication {
        configureManager("docs")

        val spec = client.get("/openapi.yaml")
        val docs = client.get("/docs")

        assertEquals(HttpStatusCode.OK, spec.status)
        assertContains(spec.bodyAsText(), "openapi: 3.1.0")
        assertEquals(HttpStatusCode.OK, docs.status)
        assertContains(docs.bodyAsText(), "swagger", ignoreCase = true)
    }

    @Test
    fun `malformed json is a bad request, not a server error`() = testApplication {
        configureManager("malformed")

        val response = client.post("/api/v1/sessions") {
            bearerAuth(TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"maxDevices":""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "\"error\"")
    }

    private fun ApplicationTestBuilder.configureManager(name: String) {
        val stateStore = StateStore(File(tempDir, "$name.db").absolutePath)
        val providerRegistry = createRouteProviderRegistry(tempDir, "$name.yaml", listOf(RouteTestProvider()))
        application { configureServer(managerServices(providerRegistry, stateStore)) }
    }

    private fun assertMetric(body: String, pattern: String) {
        assertTrue(
            Regex(pattern).containsMatchIn(body),
            "No metric matching /$pattern/ in:\n" + body.lines().filter { it.startsWith("msh_") }.joinToString("\n")
        )
    }
}
