package dev.shepherd.domain

import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.DEVICE_TYPE_PHYSICAL
import dev.shepherd.domain.metrics.ManagerMetrics
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.provider.FakeDeviceProvider
import dev.shepherd.domain.provider.FakeProviderCatalog
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionManagerMetricsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `session lifecycle is reported to metrics`() = runTest {
        val metrics = RecordingMetrics()
        val sessionManager = createSessionManager("lifecycle.db", metrics)

        val session = sessionManager.createSession(
            requestedDevices = 2,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_PHYSICAL
        )
        sessionManager.releaseSession(session.id)
        // A second release is a no-op and must not be counted again.
        sessionManager.releaseSession(session.id)

        assertEquals(listOf<String?>(DEVICE_TYPE_PHYSICAL), metrics.created)
        assertEquals(listOf(2), metrics.allocatedDevices)
        assertEquals(listOf(SessionStatus.RELEASED), metrics.finished)
    }

    @Test
    fun `impossible request is counted as rejected, not created`() = runTest {
        val metrics = RecordingMetrics()
        val sessionManager = createSessionManager("rejected.db", metrics)

        assertFailsWith<IllegalStateException> {
            sessionManager.createSession(requestedDevices = 1, api = "30", ttlSeconds = 60)
        }

        assertEquals(listOf("no_matching_devices"), metrics.rejected)
        assertTrue(metrics.created.isEmpty())
    }

    private fun createSessionManager(storeName: String, metrics: ManagerMetrics): SessionManager {
        val provider = FakeDeviceProvider(
            name = "rack-1",
            totalDevices = 4,
            supportedApiLevels = listOf("34"),
            inventoryProfiles = listOf(
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "34", count = 4)
            )
        )
        return SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(provider)),
            stateStore = StateStore(File(tempDir, storeName).absolutePath),
            metrics = metrics
        )
    }
}

private class RecordingMetrics : ManagerMetrics {
    val created = mutableListOf<String?>()
    val rejected = mutableListOf<String>()
    val allocatedDevices = mutableListOf<Int>()
    val finished = mutableListOf<SessionStatus>()

    override fun sessionCreated(deviceType: String?) {
        created += deviceType
    }

    override fun sessionRejected(reason: String) {
        rejected += reason
    }

    override fun sessionAllocated(queueWait: Duration, devices: Int) {
        allocatedDevices += devices
    }

    override fun sessionFinished(status: SessionStatus, lifetime: Duration) {
        finished += status
    }
}
