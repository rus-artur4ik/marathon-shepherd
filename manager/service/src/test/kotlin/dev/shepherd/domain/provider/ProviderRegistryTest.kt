package dev.shepherd.domain.provider

import dev.shepherd.domain.model.ShepherdConfig
import dev.shepherd.domain.model.ProviderConfig
import dev.shepherd.infra.config.ConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderRegistryTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `should rebuild active providers after config update`() {
        val configFile = File(tempDir, "config.yaml")
        configFile.writeText(
            """
            providers:
              - name: "rack-1"
                url: "http://127.0.0.1:8091"
            """.trimIndent()
        )
        val httpClient = HttpClient(CIO)
        try {
            val registry = ProviderRegistry(
                configStore = ConfigStore(configFile.absolutePath),
                httpClient = httpClient,
                providerFactory = { providerConfig, _ ->
                    FakeDeviceProvider(name = providerConfig.name)
                }
            )

            val updatedConfig = registry.updateConfig(
                ShepherdConfig(
                    providers = listOf(
                        ProviderConfig(name = "farm-1", url = "http://127.0.0.1:8092")
                    )
                )
            )

            assertEquals(listOf("farm-1"), registry.activeProviders().map { provider -> provider.name })
            assertEquals("farm-1", updatedConfig.providers.single().name)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `should keep removed providers resolvable for later lease cleanup`() {
        val configFile = File(tempDir, "config.yaml")
        configFile.writeText(
            """
            providers:
              - name: "rack-1"
                url: "http://127.0.0.1:8091"
            """.trimIndent()
        )
        val httpClient = HttpClient(CIO)
        try {
            val registry = ProviderRegistry(
                configStore = ConfigStore(configFile.absolutePath),
                httpClient = httpClient,
                providerFactory = { providerConfig, _ ->
                    FakeDeviceProvider(name = providerConfig.name)
                }
            )

            registry.updateConfig(ShepherdConfig(providers = emptyList()))

            assertTrue(registry.activeProviders().isEmpty())
            assertNotNull(registry.resolveProvider("rack-1"))
        } finally {
            httpClient.close()
        }
    }
}
