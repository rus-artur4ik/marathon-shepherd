package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDevice
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.AdapterLease
import dev.shepherd.domain.model.AdbServer

/**
 * Abstraction over any device source — physical rack, emulator farm, cloud service, etc.
 * Shepherd doesn't know how devices are managed; it only talks through this contract.
 */
interface DeviceProvider {

    /** Unique identifier for this provider (matches config name). */
    val name: String

    /** ADB server coordinates that marathon should connect to. */
    val adbServer: AdbServer

    /** Structured adapter access descriptor for operator-facing APIs. */
    val access: AdapterAccess

    /** Adapter capabilities published by the remote control plane. */
    val capabilities: AdapterCapabilities

    /** Aggregated inventory descriptors published by the adapter. */
    val inventory: List<AdapterDeviceProfile>

    /** Individual devices from the last status, for adapters that report them. */
    val devices: List<AdapterDevice> get() = emptyList()

    /** Query current device availability without side effects. */
    suspend fun queryDevices(): DevicePoolStatus

    /**
     * Acquire [count] devices for a session.
     * Returns actual number acquired (may be less than requested).
     */
    suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult

    /**
     * Acquire with a device selection. Providers that cannot select devices serve only
     * untargeted selections, by falling back to [acquire].
     */
    suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long, selection: DeviceSelection): AcquireResult =
        if (selection.isTargeted) AcquireResult(leaseId = "", acquiredCount = 0) else acquire(count, apiLevel, ttlSeconds)

    /** Returns true only when the provider can safely satisfy the requested API level. */
    fun canAllocateApiLevel(apiLevel: String): Boolean

    /**
     * Returns true when this provider supports the requested device type.
     * Empty [AdapterCapabilities.supportedDeviceTypes] means all types are supported.
     */
    fun supportsDeviceType(deviceType: String): Boolean

    /** True when the adapter declared [feature] in its capabilities. */
    fun supportsFeature(feature: String): Boolean = feature in capabilities.features

    /** Release devices previously acquired for [leaseId]. */
    suspend fun release(leaseId: String)

    /** Keeps [leaseId] for [ttlSeconds] from now; false when the adapter refused or cannot. */
    suspend fun renew(leaseId: String, ttlSeconds: Long): Boolean = false

    /** Every lease the adapter holds, or null when it cannot list them. */
    suspend fun leases(): List<AdapterLease>? = null

    /** Lightweight health probe. */
    suspend fun isHealthy(): Boolean
}

typealias DevicePoolStatus = dev.shepherd.protocol.PoolStatus

/** Which devices an acquire may pick; adapter-local ids. */
data class DeviceSelection(
    val deviceIds: List<String> = emptyList(),
    val excludeDeviceIds: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val sessionId: String? = null
) {
    /** Needs an adapter that can pick specific devices. */
    val isTargeted: Boolean get() = deviceIds.isNotEmpty() || labels.isNotEmpty()

    companion object {
        val ANY = DeviceSelection()
    }
}

data class AcquireResult(
    val leaseId: String,
    val acquiredCount: Int,
    val adbServers: List<AdbServer> = emptyList(),
    /** Devices in the lease, when the adapter names them. */
    val devices: List<LeasedDevice> = emptyList()
)

data class LeasedDevice(
    /** Adapter-local device id. */
    val id: String,
    val adbServer: AdbServer? = null
)
