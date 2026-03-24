package dev.shepherd.domain

import dev.shepherd.adapter.api.*
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.provider.AcquireResult
import dev.shepherd.domain.provider.DevicePoolStatus
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.FakeProviderCatalog
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class SessionManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `should mark session as failed and roll back leases when first provider acquires but second throws`() = runTest {
        val stateStore = StateStore(File(tempDir, "test.db").absolutePath)
        val successfulProvider = RecordingDeviceProvider(name = "rack-1")
        val throwingProvider = RecordingDeviceProvider(name = "rack-2", throwOnAcquire = true)

        val sessionManager = SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(successfulProvider, throwingProvider)),
            stateStore = stateStore
        )

        val error = assertFailsWith<IllegalStateException> {
            // Request 2 devices — rack-1 gives 1, rack-2 throws during acquire
            sessionManager.createSession(requestedDevices = 2, apiLevel = "34", ttlSeconds = 60)
        }

        val sessionId = Regex("sess_[A-Za-z0-9]+")
            .find(error.message.orEmpty())
            ?.value
            ?: error("Session id was not present in the exception message: ${error.message}")
        val session = stateStore.getSession(sessionId)

        assertEquals(listOf("lease_1"), successfulProvider.releasedLeaseIds)
        assertNotNull(session)
        assertEquals(SessionStatus.FAILED, session.status)
    }

    @Test
    fun `should skip providers that cannot safely satisfy requested api level`() = runTest {
        val stateStore = StateStore(File(tempDir, "api-filter.db").absolutePath)
        val incompatibleProvider = RecordingDeviceProvider(
            name = "mixed-rack",
            inventory = listOf(
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "33", count = 1),
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "34", count = 1)
            ),
            supportsSelectiveApiAllocation = false
        )
        val compatibleProvider = RecordingDeviceProvider(
            name = "api34-rack",
            inventory = listOf(
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "34", count = 2)
            ),
            supportsSelectiveApiAllocation = false
        )
        val sessionManager = SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(incompatibleProvider, compatibleProvider)),
            stateStore = stateStore
        )

        val session = sessionManager.createSession(requestedDevices = 1, apiLevel = "34", ttlSeconds = 60)

        assertEquals(0, incompatibleProvider.acquireCalls)
        assertEquals(1, compatibleProvider.acquireCalls)
        assertEquals(1, session.allocatedDevices)
    }

    @Test
    fun `should skip providers that do not match requested device type`() = runTest {
        val stateStore = StateStore(File(tempDir, "device-type.db").absolutePath)
        val physicalProvider = RecordingDeviceProvider(
            name = "rack-1",
            supportedDeviceTypes = listOf(DEVICE_TYPE_PHYSICAL)
        )
        val emulatorProvider = RecordingDeviceProvider(
            name = "emu-1",
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR)
        )
        val sessionManager = SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(physicalProvider, emulatorProvider)),
            stateStore = stateStore
        )

        val session = sessionManager.createSession(
            requestedDevices = 1,
            apiLevel = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        assertEquals(0, physicalProvider.acquireCalls)
        assertEquals(1, emulatorProvider.acquireCalls)
        assertEquals(1, session.allocatedDevices)
    }

    @Test
    fun `should reject unsupported requested device type`() = runTest {
        val stateStore = StateStore(File(tempDir, "device-type-validation.db").absolutePath)
        val sessionManager = SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(RecordingDeviceProvider(name = "rack-1"))),
            stateStore = stateStore
        )

        val error = assertFailsWith<IllegalArgumentException> {
            sessionManager.createSession(
                requestedDevices = 1,
                apiLevel = "34",
                ttlSeconds = 60,
                deviceType = "tablet"
            )
        }

        assertTrue(error.message.orEmpty().contains("Unsupported deviceType 'tablet'"))
    }

    @Test
    fun `should use lease scoped adb servers returned by provider acquire`() = runTest {
        val stateStore = StateStore(File(tempDir, "lease-scoped-adb.db").absolutePath)
        val provider = RecordingDeviceProvider(
            name = "rack-1",
            adbServer = AdbServer("127.0.0.1", 5037),
            acquiredCount = 2,
            acquiredAdbServers = listOf(
                AdbServer("127.0.0.1", 7601),
                AdbServer("127.0.0.1", 7602)
            )
        )
        val sessionManager = SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(provider)),
            stateStore = stateStore
        )

        val session = sessionManager.createSession(requestedDevices = 2, apiLevel = "34", ttlSeconds = 60)

        assertEquals(
            listOf(AdbServer("127.0.0.1", 7601), AdbServer("127.0.0.1", 7602)),
            session.adbServers
        )
    }

    @Test
    fun `should reject lease scoped provider that returns no adb servers`() = runTest {
        val stateStore = StateStore(File(tempDir, "missing-lease-scoped-adb.db").absolutePath)
        val provider = RecordingDeviceProvider(
            name = "rack-1",
            adbServer = AdbServer("127.0.0.1", 5037),
            acquiredCount = 1,
            acquiredAdbServers = emptyList(),
            capabilitiesMetadata = mapOf("accessIsolation" to "lease-scoped-proxy")
        )
        val sessionManager = SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(provider)),
            stateStore = stateStore
        )

        val error = assertFailsWith<IllegalArgumentException> {
            sessionManager.createSession(requestedDevices = 1, apiLevel = "34", ttlSeconds = 60)
        }

        assertTrue(error.message.orEmpty().contains("did not return lease-scoped adbServers"))
    }
}

private class RecordingDeviceProvider(
    override val name: String,
    override val adbServer: AdbServer = AdbServer("127.0.0.1", 5037),
    override val inventory: List<AdapterDeviceProfile> = listOf(
        AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "34", count = 1)
    ),
    val supportedDeviceTypes: List<String> = listOf(DEVICE_TYPE_PHYSICAL),
    private val supportsSelectiveApiAllocation: Boolean = true,
    private val throwOnAcquire: Boolean = false,
    private val acquiredCount: Int = 1,
    private val acquiredAdbServers: List<AdbServer> = listOf(adbServer),
    private val capabilitiesMetadata: Map<String, String> = emptyMap()
) : DeviceProvider {
    val releasedLeaseIds: MutableList<String> = mutableListOf()
    var acquireCalls: Int = 0

    override val access: AdapterAccess = AdapterAccess(
        preferredConnectionId = "$name-primary-adb",
        connections = listOf(
            AdapterConnection(
                id = "$name-primary-adb",
                protocol = ACCESS_PROTOCOL_ADB,
                transport = ACCESS_TRANSPORT_TCP,
                host = adbServer.host,
                port = adbServer.port,
                exposure = ACCESS_EXPOSURE_DIRECT_TCP,
                auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK),
                metadata = mapOf("scope" to "test")
            )
        )
    )
    override val capabilities: AdapterCapabilities = AdapterCapabilities(
        supportedDeviceTypes = supportedDeviceTypes,
        supportedApiLevels = inventory.mapNotNull { it.apiLevel }.distinct(),
        supportsSelectiveApiAllocation = supportsSelectiveApiAllocation,
        metadata = capabilitiesMetadata
    )

    override suspend fun queryDevices(): DevicePoolStatus {
        val total = inventory.sumOf { it.count }
        return DevicePoolStatus(available = total, busy = 0, total = total)
    }

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult {
        acquireCalls += 1
        if (throwOnAcquire) throw RuntimeException("Simulated acquire failure on $name")
        return AcquireResult(
            leaseId = "lease_1",
            acquiredCount = acquiredCount,
            adbServers = acquiredAdbServers
        )
    }

    override fun supportsDeviceType(deviceType: String): Boolean {
        return capabilities.supportedDeviceTypes.isEmpty() || deviceType in capabilities.supportedDeviceTypes
    }

    override fun canAllocateApiLevel(apiLevel: String): Boolean {
        if (capabilities.supportedApiLevels.isEmpty()) return true
        if (capabilities.supportsSelectiveApiAllocation) return apiLevel in capabilities.supportedApiLevels
        val apiLevels = inventory.mapNotNull { it.apiLevel }.toSet()
        return apiLevels.size == 1 && apiLevels.single() == apiLevel
    }

    override suspend fun release(leaseId: String) {
        releasedLeaseIds += leaseId
    }

    override suspend fun isHealthy(): Boolean = true
}
