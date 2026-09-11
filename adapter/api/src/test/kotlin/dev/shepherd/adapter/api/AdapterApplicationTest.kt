package dev.shepherd.adapter.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdapterApplicationTest {

    @Test
    fun `metrics are public and record pool and lease outcomes`() = testApplication {
        val metrics = AdapterMetrics(adapterType = "fake")
        application { configureAdapterApplication(FakeHandler(), testEnv(secret = SECRET), metrics) }

        val acquire = client.post("/acquire") {
            bearerAuth(SECRET)
            contentType(ContentType.Application.Json)
            setBody("""{"count":2,"apiLevel":"34","ttlSeconds":60}""")
        }
        assertEquals(HttpStatusCode.OK, acquire.status)
        assertEquals(HttpStatusCode.OK, client.get("/status") { bearerAuth(SECRET) }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/release/lease-1") { bearerAuth(SECRET) }.status)

        val response = client.get("/metrics")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertMetric(body, """msh_adapter_acquire_total\{outcome="full"\} 1(\.0)?""")
        assertMetric(body, """msh_adapter_acquired_devices_total 2(\.0)?""")
        assertMetric(body, """msh_adapter_pool_devices\{state="available"\} 3(\.0)?""")
        assertMetric(body, """msh_adapter_release_total\{outcome="success"\} 1(\.0)?""")
        assertMetric(body, """msh_build_info\{.*adapter_type="fake".*\} 1(\.0)?""")
        assertMetric(body, """ktor_http_server_requests_seconds_count\{""")
    }

    @Test
    fun `protected routes still require the adapter secret`() = testApplication {
        application { configureAdapterApplication(FakeHandler(), testEnv(secret = SECRET)) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/status").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    private fun assertMetric(body: String, pattern: String) {
        assertTrue(
            Regex(pattern).containsMatchIn(body),
            "No metric matching /$pattern/ in:\n" + body.lines().filter { it.startsWith("msh_") }.joinToString("\n")
        )
    }

    private fun testEnv(secret: String) = AdapterEnv(
        port = 0,
        advertisedAdbPort = 5037,
        secret = secret,
        accessMode = ACCESS_EXPOSURE_DIRECT_TCP,
        accessAuthType = ACCESS_AUTH_NETWORK,
        accessScope = "test"
    )

    private companion object {
        const val SECRET = "s3cret"
    }
}

private class FakeHandler : AdapterHandler(adapterType = "fake") {
    override suspend fun isHealthy(): Boolean = true

    override suspend fun status(): AdapterStatus = AdapterStatus(pool = AdapterPool(available = 3, busy = 1, total = 4))

    override suspend fun acquire(request: AcquireRequest): AcquireResult = AcquireResult(leaseId = "lease-1", acquiredCount = request.count)

    override suspend fun release(leaseId: String): Boolean = true
}
