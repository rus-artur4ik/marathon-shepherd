package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.*
import dev.shepherd.domain.model.AdbServer
import java.util.*

/**
 * Controllable fake for unit tests. Always healthy, always returns configured pool size.
 */
class FakeDeviceProvider(
    override val name: String,
    override val adbServer: AdbServer = AdbServer("localhost", 5037),
    private val totalDevices: Int = 4,
    private val supportedApiLevels: List<String> = emptyList(),
    private val supportsSelectiveApiAllocation: Boolean = true,
    private val inventoryProfiles: List<AdapterDeviceProfile> = emptyList(),
    private val shouldFail: Boolean = false
) : DeviceProvider {

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
        supportedDeviceTypes = listOf(DEVICE_TYPE_PHYSICAL),
        supportedApiLevels = supportedApiLevels,
        supportsSelectiveApiAllocation = supportsSelectiveApiAllocation
    )
    override val inventory: List<AdapterDeviceProfile> = inventoryProfiles

    override suspend fun queryDevices(): DevicePoolStatus {
        return DevicePoolStatus(available = totalDevices, busy = 0, total = totalDevices)
    }

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult {
        if (shouldFail) return AcquireResult(leaseId = "", acquiredCount = 0)
        val acquired = minOf(count, totalDevices)
        return AcquireResult(
            leaseId = "fake_${UUID.randomUUID().toString().take(8)}",
            acquiredCount = acquired,
            adbServers = listOf(adbServer)
        )
    }

    override fun supportsDeviceType(deviceType: String): Boolean {
        val supported = capabilities.supportedDeviceTypes
        return supported.isEmpty() || deviceType in supported
    }

    override fun canAllocateApiLevel(apiLevel: String): Boolean {
        if (supportedApiLevels.isEmpty()) {
            return true
        }
        if (supportsSelectiveApiAllocation) {
            return apiLevel in supportedApiLevels
        }
        val presentApiLevels: Set<String> = inventoryProfiles.mapNotNull { profile -> profile.apiLevel }.toSet()
        if (presentApiLevels.isEmpty()) {
            return false
        }
        return presentApiLevels.size == 1 && presentApiLevels.single() == apiLevel
    }

    override suspend fun release(leaseId: String) {
        // no-op
    }

    override suspend fun isHealthy(): Boolean = !shouldFail
}
