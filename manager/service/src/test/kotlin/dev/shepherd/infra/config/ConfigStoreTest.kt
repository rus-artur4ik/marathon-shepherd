package dev.shepherd.infra.config

import dev.shepherd.domain.model.ShepherdConfig
import dev.shepherd.domain.model.ProviderConfig
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `should load providers from yaml`() {
        val configFile = File(tempDir, "config.yaml")
        configFile.writeText(
            """
            providers:
              - name: "rack-1"
                url: "http://192.168.1.10:8091"
              - name: "emu-farm-1"
                url: "http://192.168.1.20:8092"
            """.trimIndent()
        )

        val store = ConfigStore(configFile.absolutePath)
        val config = store.loadConfig()

        assertEquals(2, config.providers.size)
        assertEquals("rack-1", config.providers[0].name)
        assertEquals("http://192.168.1.10:8091", config.providers[0].url)
        assertEquals("emu-farm-1", config.providers[1].name)
        assertEquals("http://192.168.1.20:8092", config.providers[1].url)
    }

    @Test
    fun `should cache config and not reload on repeated calls`() {
        val configFile = File(tempDir, "config.yaml")
        configFile.writeText(
            """
            providers:
              - name: "host1"
                url: "http://10.0.0.1:8091"
            """.trimIndent()
        )

        val store = ConfigStore(configFile.absolutePath)
        val first = store.loadConfig()
        val second = store.loadConfig()

        assertEquals(first, second)
    }

    @Test
    fun `should throw when config file not found`() {
        val store = ConfigStore("/nonexistent/path.yaml")
        assertFailsWith<IllegalStateException> { store.loadConfig() }
    }

    @Test
    fun `should update config and persist to file`() {
        val configFile = File(tempDir, "config.yaml")
        configFile.writeText(
            """
            providers:
              - name: "original"
                url: "http://10.0.0.1:8091"
            """.trimIndent()
        )

        val store = ConfigStore(configFile.absolutePath)
        val newConfig = ShepherdConfig(
            providers = listOf(
                ProviderConfig(name = "updated", url = "http://10.0.0.2:8092")
            )
        )
        store.updateConfig(newConfig)

        val reloaded = store.reloadConfig()
        assertEquals(1, reloaded.providers.size)
        assertEquals("updated", reloaded.providers[0].name)
        assertEquals("http://10.0.0.2:8092", reloaded.providers[0].url)
    }
}
