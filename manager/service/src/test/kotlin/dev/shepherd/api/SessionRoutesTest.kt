package dev.shepherd.api

import dev.shepherd.configureServer
import dev.shepherd.domain.DeviceAllocator
import dev.shepherd.domain.SessionManager
import dev.shepherd.infra.state.StateStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SessionRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `create session should accept maxDevices and api selector`() = testApplication {
        val stateStore = StateStore(File(tempDir, "create.db").absolutePath)
        val provider = RouteTestProvider(availableDevices = 1)
        val providerRegistry = createRouteProviderRegistry(tempDir, "create.yaml", listOf(provider))
        val sessionManager = SessionManager(providerRegistry, stateStore)
        val deviceAllocator = DeviceAllocator(providerRegistry)

        application {
            configureServer(sessionManager, deviceAllocator, providerRegistry)
        }

        val response = client.post("/api/v1/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"maxDevices":1,"api":">=34","deviceType":"emulator","ttlSeconds":120}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertContains(body, "\"status\": \"READY\"")
        assertContains(body, "\"api\": \">=34\"")
        assertContains(body, "\"deviceType\": \"emulator\"")
        assertContains(body, "\"allocatedDevices\": 1")
    }

    @Test
    fun `wait endpoint should promote pending session and request aliases should work`() = testApplication {
        val stateStore = StateStore(File(tempDir, "wait.db").absolutePath)
        val provider = RouteTestProvider(availableDevices = 1, availableAfterQueryCount = 4)
        val providerRegistry = createRouteProviderRegistry(tempDir, "wait.yaml", listOf(provider))
        val sessionManager = SessionManager(providerRegistry, stateStore)
        val deviceAllocator = DeviceAllocator(providerRegistry)

        application {
            configureServer(sessionManager, deviceAllocator, providerRegistry)
        }

        val createResponse = client.post("/api/v1/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"devices":1,"apiLevel":"34","deviceType":"emulator","ttlSeconds":120}""")
        }
        val createBody = createResponse.bodyAsText()

        assertEquals(HttpStatusCode.Created, createResponse.status)
        assertContains(createBody, "\"status\": \"PENDING\"")
        assertContains(createBody, "\"queuePosition\": 1")

        val sessionId = Regex("\"id\": \"([^\"]+)\"").find(createBody)?.groupValues?.get(1)
        requireNotNull(sessionId)

        val waitResponse = client.post("/api/v1/sessions/$sessionId/wait") {
            contentType(ContentType.Application.Json)
            setBody("""{"timeoutSeconds":2}""")
        }
        val waitBody = waitResponse.bodyAsText()

        assertEquals(HttpStatusCode.OK, waitResponse.status)
        assertContains(waitBody, "\"status\": \"READY\"")
        assertContains(waitBody, "\"allocatedDevices\": 1")

        val releaseResponse = client.delete("/api/v1/sessions/$sessionId")
        assertEquals(HttpStatusCode.OK, releaseResponse.status)
    }

    @Test
    fun `wait endpoint should return pending when timeout expires`() = testApplication {
        val stateStore = StateStore(File(tempDir, "wait-timeout.db").absolutePath)
        val provider = RouteTestProvider(availableDevices = 0, availableAfterQueryCount = Int.MAX_VALUE)
        val providerRegistry = createRouteProviderRegistry(tempDir, "wait-timeout.yaml", listOf(provider))
        val sessionManager = SessionManager(providerRegistry, stateStore)
        val deviceAllocator = DeviceAllocator(providerRegistry)

        application {
            configureServer(sessionManager, deviceAllocator, providerRegistry)
        }

        val createResponse = client.post("/api/v1/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"maxDevices":1,"api":"34","deviceType":"emulator","ttlSeconds":120}""")
        }
        val sessionId = Regex("\"id\": \"([^\"]+)\"").find(createResponse.bodyAsText())?.groupValues?.get(1)
        requireNotNull(sessionId)

        val waitResponse = client.post("/api/v1/sessions/$sessionId/wait") {
            contentType(ContentType.Application.Json)
            setBody("""{"timeoutSeconds":1}""")
        }

        assertEquals(HttpStatusCode.OK, waitResponse.status)
        assertContains(waitResponse.bodyAsText(), "\"status\": \"PENDING\"")
    }

    @Test
    fun `session routes should return not found for unknown ids`() = testApplication {
        val stateStore = StateStore(File(tempDir, "missing.db").absolutePath)
        val providerRegistry = createRouteProviderRegistry(tempDir, "missing.yaml", listOf(RouteTestProvider()))
        val sessionManager = SessionManager(providerRegistry, stateStore)
        val deviceAllocator = DeviceAllocator(providerRegistry)

        application {
            configureServer(sessionManager, deviceAllocator, providerRegistry)
        }

        val getResponse = client.get("/api/v1/sessions/sess_missing")
        val waitResponse = client.post("/api/v1/sessions/sess_missing/wait") {
            contentType(ContentType.Application.Json)
            setBody("""{"timeoutSeconds":1}""")
        }
        val deleteResponse = client.delete("/api/v1/sessions/sess_missing")

        assertEquals(HttpStatusCode.NotFound, getResponse.status)
        assertEquals(HttpStatusCode.NotFound, waitResponse.status)
        assertEquals(HttpStatusCode.NotFound, deleteResponse.status)
    }

    @Test
    fun `list endpoint should filter by status`() = testApplication {
        val stateStore = StateStore(File(tempDir, "list.db").absolutePath)
        val readyProvider = RouteTestProvider(name = "ready-provider", availableDevices = 1)
        val pendingProvider = RouteTestProvider(name = "pending-provider", availableDevices = 0, availableAfterQueryCount = Int.MAX_VALUE)
        val providerRegistry = createRouteProviderRegistry(tempDir, "list.yaml", listOf(readyProvider, pendingProvider))
        val sessionManager = SessionManager(providerRegistry, stateStore)
        val deviceAllocator = DeviceAllocator(providerRegistry)

        application {
            configureServer(sessionManager, deviceAllocator, providerRegistry)
        }

        val readyResponse = client.post("/api/v1/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"maxDevices":1,"api":"34","deviceType":"emulator","ttlSeconds":120}""")
        }
        val readySessionId = Regex("\"id\": \"([^\"]+)\"").find(readyResponse.bodyAsText())?.groupValues?.get(1)
        requireNotNull(readySessionId)

        readyProvider.availableDevices = 0
        val pendingResponse = client.post("/api/v1/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"maxDevices":1,"api":"34","deviceType":"emulator","ttlSeconds":120}""")
        }
        val pendingSessionId = Regex("\"id\": \"([^\"]+)\"").find(pendingResponse.bodyAsText())?.groupValues?.get(1)
        requireNotNull(pendingSessionId)

        val readyList = client.get("/api/v1/sessions?status=READY")
        val pendingList = client.get("/api/v1/sessions?status=PENDING")

        assertEquals(HttpStatusCode.OK, readyList.status)
        assertContains(readyList.bodyAsText(), readySessionId)
        assertEquals(HttpStatusCode.OK, pendingList.status)
        assertContains(pendingList.bodyAsText(), pendingSessionId)
    }
}
