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
import java.time.Instant
import kotlin.test.*

class SessionManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `should roll back acquired leases when later provider throws`() = runTest {
        val stateStore = createStateStore("rollback.db")
        val successfulProvider = RecordingDeviceProvider(name = "rack-1")
        val throwingProvider = RecordingDeviceProvider(name = "rack-2", throwOnAcquire = true)
        val sessionManager = createSessionManager(stateStore, successfulProvider, throwingProvider)

        val error = assertFailsWith<IllegalStateException> {
            sessionManager.createSession(requestedDevices = 2, api = "34", ttlSeconds = 60)
        }

        val sessionId = Regex("sess_[A-Za-z0-9]+")
            .find(error.message.orEmpty())
            ?.value
        assertNotNull(sessionId)
        val session = stateStore.getSession(sessionId)

        assertEquals(listOf("lease_1"), successfulProvider.releasedLeaseIds)
        assertNotNull(session)
        assertEquals(SessionStatus.FAILED, session.status)
    }

    @Test
    fun `should allocate with api selector gte`() = runTest {
        val stateStore = createStateStore("api-gte.db")
        val provider = RecordingDeviceProvider(
            name = "emu-rack",
            inventory = listOf(
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "33", count = 1),
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "34", count = 1),
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "35", count = 1)
            ),
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR)
        )
        val sessionManager = createSessionManager(stateStore, provider)

        val session = sessionManager.createSession(
            requestedDevices = 2,
            api = ">=34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        assertEquals(SessionStatus.READY, session.status)
        assertEquals(2, session.allocatedDevices)
        assertTrue(provider.requestedApiLevels.all { apiLevel -> apiLevel in setOf("34", "35") })
    }

    @Test
    fun `should match aggregated inventory provider using supported api levels`() = runTest {
        val stateStore = createStateStore("aggregated-api.db")
        val provider = RecordingDeviceProvider(
            name = "farm",
            inventory = listOf(
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = null, count = 3)
            ),
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            supportedApiLevels = listOf("34", "35"),
            availableDevices = 2
        )
        val sessionManager = createSessionManager(stateStore, provider)

        val session = sessionManager.createSession(
            requestedDevices = 2,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        assertEquals(SessionStatus.READY, session.status)
        assertEquals(2, session.allocatedDevices)
        assertEquals(listOf("34"), provider.requestedApiLevels)
    }

    @Test
    fun `should skip mixed non selective provider for ranged api selector`() = runTest {
        val stateStore = createStateStore("range-selector.db")
        val mixedRack = RecordingDeviceProvider(
            name = "mixed-rack",
            inventory = listOf(
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "33", count = 1),
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "34", count = 1)
            ),
            supportsSelectiveApiAllocation = false
        )
        val homogeneousRack = RecordingDeviceProvider(
            name = "api34-rack",
            inventory = listOf(
                AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "34", count = 2)
            ),
            supportsSelectiveApiAllocation = false
        )
        val sessionManager = createSessionManager(stateStore, mixedRack, homogeneousRack)

        val session = sessionManager.createSession(requestedDevices = 1, api = "33..34", ttlSeconds = 60)

        assertEquals(SessionStatus.READY, session.status)
        assertEquals(0, mixedRack.acquireCalls)
        assertEquals(1, homogeneousRack.acquireCalls)
    }

    @Test
    fun `should filter providers by device type`() = runTest {
        val stateStore = createStateStore("device-type.db")
        val physicalProvider = RecordingDeviceProvider(
            name = "rack-1",
            supportedDeviceTypes = listOf(DEVICE_TYPE_PHYSICAL)
        )
        val emulatorProvider = RecordingDeviceProvider(
            name = "emu-1",
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            inventory = listOf(AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "34", count = 1))
        )
        val sessionManager = createSessionManager(stateStore, physicalProvider, emulatorProvider)

        val session = sessionManager.createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        assertEquals(SessionStatus.READY, session.status)
        assertEquals(0, physicalProvider.acquireCalls)
        assertEquals(1, emulatorProvider.acquireCalls)
    }

    @Test
    fun `should reject unsupported device type`() = runTest {
        val sessionManager = createSessionManager(createStateStore("validation.db"), RecordingDeviceProvider(name = "rack"))

        val error = assertFailsWith<IllegalArgumentException> {
            sessionManager.createSession(requestedDevices = 1, api = "34", ttlSeconds = 60, deviceType = "tablet")
        }

        assertTrue(error.message.orEmpty().contains("Unsupported deviceType 'tablet'"))
    }

    @Test
    fun `should return pending session when matching devices are registered but busy`() = runTest {
        val stateStore = createStateStore("pending.db")
        val provider = RecordingDeviceProvider(
            name = "emu-rack",
            inventory = listOf(AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "34", count = 2)),
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            availableDevices = 0
        )
        val sessionManager = createSessionManager(stateStore, provider)

        val session = sessionManager.createSession(
            requestedDevices = 2,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        assertEquals(SessionStatus.PENDING, session.status)
        assertEquals(0, session.allocatedDevices)
        assertEquals(1, sessionManager.getQueuePosition(session.id))
    }

    @Test
    fun `wait should promote pending session once devices become available`() = runTest {
        val stateStore = createStateStore("wait-ready.db")
        val provider = RecordingDeviceProvider(
            name = "emu-rack",
            inventory = listOf(AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "34", count = 1)),
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            availableDevices = 0
        )
        val sessionManager = createSessionManager(stateStore, provider)
        val session = sessionManager.createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        provider.availableDevices = 1
        val readySession = sessionManager.waitForSession(session.id, timeoutSeconds = 2)

        assertEquals(SessionStatus.READY, readySession.status)
        assertEquals(1, readySession.allocatedDevices)
    }

    @Test
    fun `wait should respect queue order and keep later session pending`() = runTest {
        val stateStore = createStateStore("queue-order.db")
        val provider = RecordingDeviceProvider(
            name = "emu-rack",
            inventory = listOf(AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "34", count = 1)),
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            availableDevices = 0
        )
        val sessionManager = createSessionManager(stateStore, provider)
        val firstSession = sessionManager.createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )
        val secondSession = sessionManager.createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        provider.availableDevices = 1
        val stillPending = sessionManager.waitForSession(secondSession.id, timeoutSeconds = 1)

        assertEquals(SessionStatus.PENDING, stillPending.status)
        assertEquals(2, sessionManager.getQueuePosition(secondSession.id))
        val firstReady = sessionManager.waitForSession(firstSession.id, timeoutSeconds = 1)
        assertEquals(SessionStatus.READY, firstReady.status)
        assertEquals(1, sessionManager.getQueuePosition(secondSession.id))
    }

    @Test
    fun `stale head session should be evicted so next session can allocate`() = runTest {
        val stateStore = createStateStore("stale-head.db")
        val provider = RecordingDeviceProvider(
            name = "emu-rack",
            inventory = listOf(AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "34", count = 1)),
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            availableDevices = 0
        )
        val sessionManager = createSessionManager(stateStore, provider)
        val staleSession = sessionManager.createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )
        val queuedSession = sessionManager.createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        val persistedStaleSession = stateStore.getSession(staleSession.id)
        assertNotNull(persistedStaleSession)
        stateStore.updateSession(
            persistedStaleSession.copy(lastHeartbeatAt = Instant.now().minusSeconds(120))
        )
        provider.availableDevices = 1

        val readySession = sessionManager.waitForSession(queuedSession.id, timeoutSeconds = 2)

        assertEquals(SessionStatus.READY, readySession.status)
        assertEquals(SessionStatus.FAILED, stateStore.getSession(staleSession.id)?.status)
    }

    @Test
    fun `should reject lease scoped provider that returns no adb servers`() = runTest {
        val provider = RecordingDeviceProvider(
            name = "rack-1",
            acquiredAdbServers = emptyList(),
            capabilitiesMetadata = mapOf("accessIsolation" to "lease-scoped-proxy")
        )
        val sessionManager = createSessionManager(createStateStore("missing-adb.db"), provider)

        val error = assertFailsWith<IllegalArgumentException> {
            sessionManager.createSession(requestedDevices = 1, api = "34", ttlSeconds = 60)
        }

        assertTrue(error.message.orEmpty().contains("did not return lease-scoped adbServers"))
    }

    @Test
    fun `should allow on-demand cuttlefish provider with zero initial inventory`() = runTest {
        val provider = RecordingDeviceProvider(
            name = "integration-cuttlefish",
            inventory = emptyList(),
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            supportedApiLevels = listOf("34", "35"),
            capabilityFeatures = listOf("ephemeral-vm"),
            availableDevices = 0,
            onDemandAcquireCount = 1
        )
        val sessionManager = createSessionManager(createStateStore("on-demand-cuttlefish.db"), provider)

        val session = sessionManager.createSession(
            requestedDevices = 1,
            api = "35",
            ttlSeconds = 60,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        assertEquals(SessionStatus.READY, session.status)
        assertEquals(1, session.allocatedDevices)
        assertEquals(listOf("35"), provider.requestedApiLevels)
    }

    @Test
    fun `should fail immediately when no registered devices match selector`() = runTest {
        val provider = RecordingDeviceProvider(
            name = "physical-rack",
            inventory = listOf(AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "33", count = 1))
        )
        val sessionManager = createSessionManager(createStateStore("no-match.db"), provider)

        val error = assertFailsWith<IllegalStateException> {
            sessionManager.createSession(
                requestedDevices = 1,
                api = ">=34",
                ttlSeconds = 60,
                deviceType = DEVICE_TYPE_EMULATOR
            )
        }

        assertTrue(error.message.orEmpty().contains("No registered devices match the request"))
    }

    private fun createStateStore(name: String): StateStore {
        return StateStore(File(tempDir, name).absolutePath)
    }

    private fun createSessionManager(stateStore: StateStore, vararg providers: RecordingDeviceProvider): SessionManager {
        return SessionManager(
            providerCatalog = FakeProviderCatalog(active = providers.toList()),
            stateStore = stateStore
        )
    }
}

private class RecordingDeviceProvider(
    override val name: String,
    override val adbServer: AdbServer = AdbServer("127.0.0.1", 5037),
    override val inventory: List<AdapterDeviceProfile> = listOf(
        AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = "34", count = 1)
    ),
    val supportedDeviceTypes: List<String> = listOf(DEVICE_TYPE_PHYSICAL),
    val supportedApiLevels: List<String> = inventory.mapNotNull { it.apiLevel }.distinct(),
    private val supportsSelectiveApiAllocation: Boolean = true,
    private val throwOnAcquire: Boolean = false,
    private val acquiredAdbServers: List<AdbServer> = listOf(adbServer),
    private val capabilitiesMetadata: Map<String, String> = emptyMap(),
    private val capabilityFeatures: List<String> = emptyList(),
    private val onDemandAcquireCount: Int = 0,
    var availableDevices: Int = inventory.sumOf { profile -> profile.count },
) : DeviceProvider {
    val releasedLeaseIds: MutableList<String> = mutableListOf()
    val requestedApiLevels: MutableList<String> = mutableListOf()
    private val leaseCounts: MutableMap<String, Int> = mutableMapOf()
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
        supportedApiLevels = supportedApiLevels,
        supportsSelectiveApiAllocation = supportsSelectiveApiAllocation,
        features = capabilityFeatures,
        metadata = capabilitiesMetadata
    )

    override suspend fun queryDevices(): DevicePoolStatus {
        val total = inventory.sumOf { profile -> profile.count }
        return DevicePoolStatus(
            available = availableDevices.coerceAtLeast(0),
            busy = (total - availableDevices).coerceAtLeast(0),
            total = total
        )
    }

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult {
        acquireCalls += 1
        requestedApiLevels += apiLevel
        if (throwOnAcquire) {
            throw RuntimeException("Simulated acquire failure on $name")
        }
        val actualCount = if (onDemandAcquireCount > 0) {
            minOf(count, onDemandAcquireCount)
        } else {
            minOf(count, availableDevices)
        }
        val leaseId = "lease_$acquireCalls"
        if (onDemandAcquireCount <= 0) {
            availableDevices -= actualCount
        }
        leaseCounts[leaseId] = actualCount
        return AcquireResult(
            leaseId = leaseId,
            acquiredCount = actualCount,
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
        availableDevices += leaseCounts.remove(leaseId) ?: 0
    }

    override suspend fun isHealthy(): Boolean = true
}
