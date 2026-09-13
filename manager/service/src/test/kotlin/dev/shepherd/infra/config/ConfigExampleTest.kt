package dev.shepherd.infra.config

import dev.shepherd.domain.model.QueuePolicy
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps deploy/msh.yaml.example honest: with every optional top-level section uncommented it
 * must still load, so a renamed or removed setting fails here instead of in someone's deploy.
 */
class ConfigExampleTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the shipped example loads with every optional section enabled`() {
        val example = File(REPO_ROOT, "deploy/msh.yaml.example")
        val enabled = example.readLines().joinToString("\n") { line ->
            // Top-level commented settings ("# key: ..." / "#   key: ...") are switched on;
            // prose comments and the indented provider samples stay commented.
            OPTIONAL_SETTING.find(line)?.groupValues?.get(1) ?: line
        }
        val file = File(tempDir, "msh.yaml")
        file.writeText(enabled)

        val config = ConfigStore(file.absolutePath).loadConfig()

        assertEquals(listOf("device-rack-1"), config.providers.map { it.name })
        assertEquals(8, config.quotas.defaults.maxDevices)
        assertEquals(QueuePolicy.FIFO, config.scheduler.policy)
        assertEquals(90, config.registration.ttlSeconds)
        assertEquals(300, config.reconciliation.intervalSeconds)
        assertEquals(600, config.adapterTimeouts.acquireSeconds)
        assertEquals(30, config.sessions.retentionDays)
        assertEquals(2, config.mcp.maxDevicesPerSession)
        assertEquals(900, config.mcp.idleTimeoutSeconds)
        assertEquals("https://shepherd.example.com", config.auth.publicUrl)
        assertEquals(10, config.auth.local.minPasswordLength)
    }

    private companion object {
        /** Tests run from the module directory, two levels below the repository root. */
        val REPO_ROOT: File = File("../..").canonicalFile
        val OPTIONAL_SETTING = Regex("^# ( *[A-Za-z]+:.*)$")
    }
}
