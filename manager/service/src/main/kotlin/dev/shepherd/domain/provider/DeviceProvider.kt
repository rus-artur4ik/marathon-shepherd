package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.domain.model.AdbServer
import kotlinx.serialization.Serializable

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

    /** Query current device availability without side effects. */
    suspend fun queryDevices(): DevicePoolStatus

    /**
     * Acquire [count] devices for a session.
     * Returns actual number acquired (may be less than requested).
     */
    suspend fun acquire(
        count: Int,
        apiLevel: String,
        ttlSeconds: Long
    ): AcquireResult

    /** Returns true only when the provider can safely satisfy the requested API level. */
    fun canAllocateApiLevel(apiLevel: String): Boolean

    /**
     * Returns true when this provider supports the requested device type.
     * Empty [AdapterCapabilities.supportedDeviceTypes] means all types are supported.
     */
    fun supportsDeviceType(deviceType: String): Boolean

    /** Release devices previously acquired for [leaseId]. */
    suspend fun release(leaseId: String)

    /** Lightweight health probe. */
    suspend fun isHealthy(): Boolean
}

@Serializable
data class DevicePoolStatus(
    val available: Int,
    val busy: Int,
    val total: Int
)

data class AcquireResult(
    val leaseId: String,
    val acquiredCount: Int
)
