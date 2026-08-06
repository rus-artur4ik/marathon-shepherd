package dev.shepherd.api

import dev.shepherd.configureServer
import dev.shepherd.domain.DeviceAllocator
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.model.REDACTED_SECRET
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.config.ConfigStore
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
import kotlin.test.assertFalse

class ConfigRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `get config should return current providers`() = testApplication {
        val provider = RouteTestProvider(name = "rack-1")
        val providerRegistry = createRouteProviderRegistry(tempDir, "config.yaml", listOf(provider))
        val stateStore = StateStore(File(tempDir, "config.db").absolutePath)

        application {
            configureServer(
                sessionManager = SessionManager(providerRegistry, stateStore),
                deviceAllocator = DeviceAllocator(providerRegistry),
                providerRegistry = providerRegistry
            )
        }

        val response = client.get("/api/v1/config")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "\"name\": \"rack-1\"")
    }

    @Test
    fun `get config should never expose adapter secrets`() = testApplication {
        val configFile = File(tempDir, "config-secret.yaml")
        configFile.writeText(
            """
            providers:
              - name: "rack-1"
                url: "http://127.0.0.1:7037"
                secret: "super-secret-token"
            """.trimIndent()
        )
        val providerRegistry = createRouteProviderRegistry(configFile, listOf(RouteTestProvider(name = "rack-1")))
        val stateStore = StateStore(File(tempDir, "config-secret.db").absolutePath)

        application {
            configureServer(
                sessionManager = SessionManager(providerRegistry, stateStore),
                deviceAllocator = DeviceAllocator(providerRegistry),
                providerRegistry = providerRegistry
            )
        }

        val body = client.get("/api/v1/config").bodyAsText()

        assertFalse(body.contains("super-secret-token"), "adapter secret leaked over the API: $body")
        assertContains(body, REDACTED_SECRET)
    }

    @Test
    fun `put config should keep the stored secret when the caller echoes the redaction placeholder`() = testApplication {
        val configFile = File(tempDir, "config-roundtrip.yaml")
        configFile.writeText(
            """
            providers:
              - name: "rack-1"
                url: "http://127.0.0.1:7037"
                secret: "super-secret-token"
            """.trimIndent()
        )
        val providerRegistry = createRouteProviderRegistry(configFile, listOf(RouteTestProvider(name = "rack-1")))
        val stateStore = StateStore(File(tempDir, "config-roundtrip.db").absolutePath)

        application {
            configureServer(
                sessionManager = SessionManager(providerRegistry, stateStore),
                deviceAllocator = DeviceAllocator(providerRegistry),
                providerRegistry = providerRegistry
            )
        }

        // A read-modify-write cycle sends the placeholder straight back; it must not be
        // persisted as the literal new secret.
        val response = client.put("/api/v1/config") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"providers":[{"name":"rack-1","url":"http://127.0.0.1:9999","secret":"$REDACTED_SECRET"}]}
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("super-secret-token", providerRegistry.currentConfig().providers.single().secret)
        assertEquals("http://127.0.0.1:9999", providerRegistry.currentConfig().providers.single().url)
    }

    @Test
    fun `put config should update active providers`() = testApplication {
        val providerOne = RouteTestProvider(name = "rack-1")
        val providerTwo = RouteTestProvider(name = "farm-1")
        val providerRegistry = createRouteProviderRegistry(tempDir, "config-update.yaml", listOf(providerOne, providerTwo))
        val stateStore = StateStore(File(tempDir, "config-update.db").absolutePath)

        application {
            configureServer(
                sessionManager = SessionManager(providerRegistry, stateStore),
                deviceAllocator = DeviceAllocator(providerRegistry),
                providerRegistry = providerRegistry
            )
        }

        val response = client.put("/api/v1/config") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"providers":[{"name":"farm-1","url":"http://127.0.0.1:7037"}]}
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "\"name\": \"farm-1\"")
    }

    @Test
    fun `put config should reject removing provider with active sessions`() = testApplication {
        val providerOne = RouteTestProvider(name = "rack-1", availableDevices = 1)
        val providerTwo = RouteTestProvider(name = "farm-1", availableDevices = 0)
        val providerRegistry = createRouteProviderRegistry(tempDir, "config-conflict.yaml", listOf(providerOne, providerTwo))
        val stateStore = StateStore(File(tempDir, "config-conflict.db").absolutePath)
        val sessionManager = SessionManager(providerRegistry, stateStore)

        application {
            configureServer(
                sessionManager = sessionManager,
                deviceAllocator = DeviceAllocator(providerRegistry),
                providerRegistry = providerRegistry
            )
        }

        client.post("/api/v1/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"maxDevices":1,"api":"34","deviceType":"emulator","ttlSeconds":120}""")
        }

        val response = client.put("/api/v1/config") {
            contentType(ContentType.Application.Json)
            setBody("""{"providers":[{"name":"farm-1","url":"http://127.0.0.1:7037"}]}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertContains(response.bodyAsText(), "Cannot remove providers with active sessions")
    }

    @Test
    fun `post reload should re-read config from disk`() = testApplication {
        val configFile = File(tempDir, "reload.yaml")
        configFile.writeText(
            """
            providers:
              - name: "rack-1"
                url: "http://127.0.0.1:7037"
            """.trimIndent()
        )
        val providerRegistry = ProviderRegistry(
            configStore = ConfigStore(configFile.absolutePath),
            httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO),
            providerFactory = { providerConfig, _ ->
                RouteTestProvider(name = providerConfig.name)
            }
        )
        val stateStore = StateStore(File(tempDir, "reload.db").absolutePath)

        application {
            configureServer(
                sessionManager = SessionManager(providerRegistry, stateStore),
                deviceAllocator = DeviceAllocator(providerRegistry),
                providerRegistry = providerRegistry
            )
        }

        configFile.writeText(
            """
            providers:
              - name: "farm-1"
                url: "http://127.0.0.1:7037"
            """.trimIndent()
        )

        val reloadResponse = client.post("/api/v1/config/reload")
        val getResponse = client.get("/api/v1/config")

        assertEquals(HttpStatusCode.OK, reloadResponse.status)
        assertContains(reloadResponse.bodyAsText(), "\"name\": \"farm-1\"")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertContains(getResponse.bodyAsText(), "\"name\": \"farm-1\"")
    }
}
