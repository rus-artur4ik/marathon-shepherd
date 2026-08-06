package dev.shepherd.adapter.farm

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FarmServerClientTest {

    @Test
    fun `getStatus should decode healthy farm response`() = runBlocking {
        val client = createMockClient { request ->
            assertEquals("/status", request.url.encodedPath)
            respond("""{"available":2,"busy":1,"total":3}""", HttpStatusCode.OK, jsonHeaders())
        }
        val farmClient = FarmServerClient("http://farm-server:8080", client)

        val status = farmClient.getStatus()

        assertEquals(FarmStatus(available = 2, busy = 1, total = 3), status)
    }

    @Test
    fun `acquireEmulators should return zero result on error response`() = runBlocking {
        val client = createMockClient { _ ->
            respond("""{"error":"busy"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders())
        }
        val farmClient = FarmServerClient("http://farm-server:8080", client)

        val result = farmClient.acquireEmulators(count = 2, apiLevel = "34", ttlSeconds = 120)

        assertEquals(FarmAcquireResult(leaseId = null, acquiredCount = 0), result)
    }

    @Test
    fun `releaseEmulators should return false on backend failure`() = runBlocking {
        val client = createMockClient { _ ->
            respond("", HttpStatusCode.InternalServerError, jsonHeaders())
        }
        val farmClient = FarmServerClient("http://farm-server:8080", client)

        assertFalse(farmClient.releaseEmulators("lease-1"))
    }

    @Test
    fun `isHealthy should reflect health endpoint success`() = runBlocking {
        val healthyClient = createMockClient { _ ->
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders())
        }
        val unhealthyClient = createMockClient { _ ->
            respond("""{"status":"broken"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders())
        }

        assertTrue(FarmServerClient("http://farm-server:8080", healthyClient).isHealthy())
        assertFalse(FarmServerClient("http://farm-server:8080", unhealthyClient).isHealthy())
    }
}

private fun createMockClient(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData
): HttpClient {
    return HttpClient(
        MockEngine { request ->
            handler(request)
        }
    ) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

private fun jsonHeaders() = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
