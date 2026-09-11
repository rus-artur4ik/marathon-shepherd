package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDevice
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.AdapterLease
import dev.shepherd.adapter.api.DEVICE_STATE_AVAILABLE
import dev.shepherd.adapter.api.DEVICE_STATE_BUSY
import dev.shepherd.adapter.api.DEVICE_TYPE_PHYSICAL
import dev.shepherd.adapter.api.FEATURE_DEVICE_SELECTION
import dev.shepherd.adapter.api.FEATURE_LEASE_LIST
import dev.shepherd.adapter.api.FEATURE_LEASE_RENEW
import dev.shepherd.domain.model.AdbServer

/**
 * An adb-rack-like provider for tests: it lists individual physical devices, honours device
 * selection and supports lease renew and listing, all in memory.
 */
class ListingProvider(
    override val name: String,
    initialDevices: List<AdapterDevice>,
    features: List<String> = listOf(FEATURE_DEVICE_SELECTION, FEATURE_LEASE_RENEW, FEATURE_LEASE_LIST)
) : DeviceProvider {
    private val devicesById = linkedMapOf<String, AdapterDevice>().apply { initialDevices.forEach { device -> put(device.id, device) } }
    private val leaseDevices = linkedMapOf<String, List<String>>()
    private var nextLease = 1

    val acquireSelections = mutableListOf<DeviceSelection>()
    val renewed = mutableListOf<Pair<String, Long>>()
    val released = mutableListOf<String>()

    /** Leases the adapter reports on top of its own, e.g. orphans left by a crashed manager. */
    var extraLeases: List<AdapterLease> = emptyList()

    override val adbServer: AdbServer = AdbServer("10.0.0.1", 5037)
    override val access: AdapterAccess = AdapterAccess()
    override val capabilities: AdapterCapabilities = AdapterCapabilities(
        supportedDeviceTypes = listOf(DEVICE_TYPE_PHYSICAL),
        supportsSelectiveApiAllocation = true,
        features = features
    )
    override val inventory: List<AdapterDeviceProfile>
        get() = devicesById.values.groupBy { device -> device.apiLevel }.map { (api, grouped) ->
            AdapterDeviceProfile(deviceType = DEVICE_TYPE_PHYSICAL, apiLevel = api, count = grouped.size)
        }
    override val devices: List<AdapterDevice> get() = devicesById.values.toList()

    fun setState(id: String, state: String, leaseId: String? = null) {
        devicesById[id] = devicesById.getValue(id).copy(state = state, leaseId = leaseId)
    }

    override suspend fun queryDevices(): DevicePoolStatus = DevicePoolStatus(
        available = devicesById.values.count { device -> device.state == DEVICE_STATE_AVAILABLE },
        busy = devicesById.values.count { device -> device.state == DEVICE_STATE_BUSY },
        total = devicesById.size
    )

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult =
        acquire(count, apiLevel, ttlSeconds, DeviceSelection.ANY)

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long, selection: DeviceSelection): AcquireResult {
        acquireSelections += selection
        val chosen = devicesById.values.filter { device ->
            device.state == DEVICE_STATE_AVAILABLE &&
                device.apiLevel == apiLevel &&
                (selection.deviceIds.isEmpty() || device.id in selection.deviceIds) &&
                device.id !in selection.excludeDeviceIds &&
                selection.labels.all { (key, value) -> device.labels[key] == value }
        }.take(count)
        if (chosen.isEmpty()) {
            return AcquireResult(leaseId = "", acquiredCount = 0)
        }
        val leaseId = "lease-${nextLease++}"
        chosen.forEach { device -> setState(device.id, DEVICE_STATE_BUSY, leaseId) }
        leaseDevices[leaseId] = chosen.map { device -> device.id }
        val endpoints = chosen.mapIndexed { index, _ -> AdbServer("10.0.0.1", PROXY_PORT_BASE + index) }
        return AcquireResult(
            leaseId = leaseId,
            acquiredCount = chosen.size,
            adbServers = endpoints,
            devices = chosen.mapIndexed { index, device -> LeasedDevice(device.id, endpoints[index]) }
        )
    }

    override fun canAllocateApiLevel(apiLevel: String): Boolean = true

    override fun supportsDeviceType(deviceType: String): Boolean = deviceType == DEVICE_TYPE_PHYSICAL

    override suspend fun release(leaseId: String) {
        released += leaseId
        leaseDevices.remove(leaseId)?.forEach { id -> setState(id, DEVICE_STATE_AVAILABLE) }
    }

    override suspend fun renew(leaseId: String, ttlSeconds: Long): Boolean {
        renewed += leaseId to ttlSeconds
        return leaseId in leaseDevices
    }

    override suspend fun leases(): List<AdapterLease> =
        leaseDevices.map { (leaseId, ids) -> AdapterLease(leaseId = leaseId, deviceIds = ids) } + extraLeases

    override suspend fun isHealthy(): Boolean = true

    companion object {
        private const val PROXY_PORT_BASE = 7600

        fun device(
            id: String,
            apiLevel: String = "34",
            state: String = DEVICE_STATE_AVAILABLE,
            labels: Map<String, String> = emptyMap()
        ): AdapterDevice = AdapterDevice(
            id = id,
            deviceType = DEVICE_TYPE_PHYSICAL,
            state = state,
            apiLevel = apiLevel,
            model = "Pixel",
            labels = labels
        )
    }
}
