package dev.shepherd.domain

import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.DEVICE_STATE_BUSY
import dev.shepherd.adapter.api.DEVICE_TYPE_PHYSICAL
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.SessionOptions
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.FakeDeviceProvider
import dev.shepherd.domain.provider.FakeProviderCatalog
import dev.shepherd.domain.provider.ListingProvider
import dev.shepherd.domain.provider.ListingProvider.Companion.device
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceAllocationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a rack whose devices are all busy queues the session instead of rejecting it`() = runTest {
        // Regression: adb inventory counts only free devices, so a full rack used to answer 503.
        val rack = ListingProvider("rack", listOf(device("A", state = DEVICE_STATE_BUSY), device("B", state = DEVICE_STATE_BUSY)))

        val session = manager(rack, "busy").createSession(requestedDevices = 1, api = "34", ttlSeconds = 60)

        assertEquals(SessionStatus.PENDING, session.status)
    }

    @Test
    fun `named devices are the only ones handed out`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A"), device("B")))

        val session = manager(rack, "named").createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 60,
            options = SessionOptions(deviceIds = listOf("rack:B"))
        )

        assertEquals(SessionStatus.READY, session.status)
        assertEquals(listOf("rack:B"), session.devices.map { it.id })
        assertEquals(listOf("B"), rack.acquireSelections.single().deviceIds)
        assertEquals(AdbServer("10.0.0.1", 7600), session.devices.single().adbServer)
        assertEquals(session.id, rack.acquireSelections.single().sessionId)
    }

    @Test
    fun `labels restrict which devices a session can get`() = runTest {
        val rack = ListingProvider(
            "rack",
            listOf(device("phone", labels = mapOf("form" to "phone")), device("tablet", labels = mapOf("form" to "tablet")))
        )

        val session = manager(rack, "labels").createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 60,
            options = SessionOptions(labels = mapOf("form" to "tablet"))
        )

        assertEquals(listOf("rack:tablet"), session.devices.map { it.id })
    }

    @Test
    fun `devices in maintenance are never allocated`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A"), device("B", state = DEVICE_STATE_BUSY)))

        val session = manager(rack, "maintenance", maintenance = setOf("rack:A"))
            .createSession(requestedDevices = 1, api = "34", ttlSeconds = 60)

        assertEquals(SessionStatus.PENDING, session.status, "A is in maintenance and B is busy, so the session waits")
        assertTrue(rack.acquireSelections.isEmpty())
    }

    @Test
    fun `maintenance on every matching device makes a request impossible`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A")))

        assertFailsWith<IllegalStateException> {
            manager(rack, "all-maintenance", maintenance = setOf("rack:A")).createSession(1, "34", 60)
        }
    }

    @Test
    fun `unknown device ids are rejected up front`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A")))

        val error = assertFailsWith<IllegalArgumentException> {
            manager(rack, "unknown").createSession(1, "34", 60, options = SessionOptions(deviceIds = listOf("rack:Z")))
        }

        assertContains(error.message.orEmpty(), "rack:Z")
    }

    @Test
    fun `targeted requests need a provider that can select devices`() = runTest {
        val farm = FakeDeviceProvider(
            name = "farm",
            totalDevices = 4,
            supportedApiLevels = listOf("34"),
            inventoryProfiles = listOf(AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "34", count = 4))
        )

        val error = assertFailsWith<IllegalStateException> {
            manager(farm, "pool-only").createSession(1, "34", 60, options = SessionOptions(labels = mapOf("form" to "tablet")))
        }

        assertContains(error.message.orEmpty(), "labels form=tablet")
    }

    private fun manager(provider: DeviceProvider, name: String, maintenance: Set<String> = emptySet()): SessionManager = SessionManager(
        providerCatalog = FakeProviderCatalog(active = listOf(provider)),
        stateStore = StateStore(File(tempDir, "$name.db").absolutePath),
        maintenanceDeviceIds = { maintenance }
    )
}
