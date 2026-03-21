package dev.shepherd.api

import dev.shepherd.configureServer
import dev.shepherd.domain.DeviceAllocator
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.provider.FakeDeviceProvider
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.config.ConfigStore
import dev.shepherd.infra.state.StateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HealthRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `should return health status with provider info`() = testApplication {
        val configFile = File(tempDir, "config.yaml")
        configFile.writeText(
            """
            providers:
              - name: "rack-1"
                url: "http://127.0.0.1:8091"
            """.trimIndent()
        )

        val configStore = ConfigStore(configFile.absolutePath)
        val stateStore = StateStore(File(tempDir, "test.db").absolutePath)
        val httpClient = HttpClient(CIO)
        val providerRegistry = ProviderRegistry(configStore, httpClient) { providerConfig, _ ->
            FakeDeviceProvider(name = providerConfig.name, totalDevices = 4)
        }
        val deviceAllocator = DeviceAllocator(providerRegistry)
        val sessionManager = SessionManager(providerRegistry, stateStore)

        try {
            application { configureServer(sessionManager, deviceAllocator, providerRegistry) }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertContains(body, "\"version\"")
            assertContains(body, "0.1.0")
            assertContains(body, "\"providersTotal\"")
            assertContains(body, "\"providersHealthy\"")
            assertContains(body, "\"providers\"")
            assertContains(body, "rack-1")
            assertContains(body, "HEALTHY")
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `should return service unavailable when no providers are healthy`() = testApplication {
        val configFile = File(tempDir, "config-unhealthy.yaml")
        configFile.writeText(
            """
            providers:
              - name: "broken-rack"
                url: "http://127.0.0.1:8091"
            """.trimIndent()
        )

        val configStore = ConfigStore(configFile.absolutePath)
        val stateStore = StateStore(File(tempDir, "test-unhealthy.db").absolutePath)
        val httpClient = HttpClient(CIO)
        val providerRegistry = ProviderRegistry(configStore, httpClient) { providerConfig, _ ->
            FakeDeviceProvider(name = providerConfig.name, totalDevices = 0, shouldFail = true)
        }
        val deviceAllocator = DeviceAllocator(providerRegistry)
        val sessionManager = SessionManager(providerRegistry, stateStore)

        try {
            application { configureServer(sessionManager, deviceAllocator, providerRegistry) }

            val response = client.get("/health")
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertContains(body, "\"status\"")
            assertContains(body, "unhealthy")
        } finally {
            httpClient.close()
        }
    }
}
