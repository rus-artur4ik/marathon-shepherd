package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.*

class AdbAdapterHandler(
    private val adbService: AdbService,
    private val leaseManager: AdbLeaseManager
) : AdapterHandler(adapterType = "adb") {

    override suspend fun isHealthy(): Boolean = adbService.isAdbReachable()

    override suspend fun status(): AdapterStatus {
        val connectedDevices: List<AdbPhysicalDevice> = adbService.listPhysicalDevices()
        val snapshot: AdbLeaseSnapshot = leaseManager.buildSnapshot(connectedDevices)
        return AdapterStatus(
            pool = AdapterPool(
                available = snapshot.availableDevices.size,
                busy = snapshot.busyDevices.size,
                total = connectedDevices.size
            ),
            access = AdapterAccess(
                metadata = mapOf(
                    "adapterType" to adapterType,
                    "accessMode" to "acquire-only",
                    "isolation" to "lease-scoped-proxy"
                )
            ),
            inventory = leaseManager.buildStatusInventory(
                availableDevices = snapshot.availableDevices,
                busyDevices = snapshot.busyDevices,
                busySerialsByLease = snapshot.busySerialsByLease
            ),
            metadata = mapOf(
                "busySerials" to snapshot.busySerialsByLease.keys.sorted().joinToString(","),
                "busyLeases" to snapshot.busySerialsByLease.entries
                    .sortedBy { entry -> entry.key }
                    .joinToString(",") { entry -> "${entry.key}@${entry.value}" }
            ).filterValues { value -> value.isNotBlank() }
        )
    }

    override suspend fun acquire(request: AcquireRequest): AcquireResult {
        val connectedDevices: List<AdbPhysicalDevice> = adbService.listPhysicalDevices()
        val acquisition: AdbLeaseAcquisition = leaseManager.acquireDevices(
            requestedCount = request.count,
            apiLevel = request.apiLevel,
            connectedDevices = connectedDevices
        )
        if (acquisition.acquiredDevices.isEmpty()) {
            return AcquireResult(leaseId = null, acquiredCount = 0)
        }
        return AcquireResult(
            leaseId = acquisition.leaseId,
            acquiredCount = acquisition.acquiredDevices.size,
            access = leaseManager.buildLeaseAccess(
                adapterType = adapterType,
                requestHost = "unknown",
                leasedDevices = acquisition.acquiredDevices
            ),
            inventory = leaseManager.buildGroupedProfiles(
                devices = acquisition.acquiredDevices.map { device ->
                    AdbPhysicalDevice(
                        serial = device.serial,
                        apiLevel = device.apiLevel,
                        manufacturer = device.manufacturer,
                        model = device.model,
                        abi = device.abi
                    )
                }
            ).map { profile ->
                profile.copy(
                    metadata = profile.metadata + mapOf(
                        "leaseId" to requireNotNull(acquisition.leaseId),
                        "proxyPorts" to acquisition.acquiredDevices
                            .filter { device -> device.apiLevel == profile.apiLevel }
                            .joinToString(",") { device -> device.proxyPort.toString() }
                    )
                )
            },
            metadata = mapOf(
                "leaseId" to requireNotNull(acquisition.leaseId),
                "busySerials" to acquisition.busySerials.entries
                    .sortedBy { entry -> entry.key }
                    .joinToString(",") { entry -> "${entry.key}@${entry.value}" },
                "missingSerials" to acquisition.missingSerials.sorted().joinToString(","),
                "isolation" to "lease-scoped-proxy"
            ).filterValues { value -> value.isNotBlank() }
        )
    }

    override suspend fun release(leaseId: String): Boolean = leaseManager.releaseLease(leaseId)

    override fun capabilities(env: AdapterEnv): AdapterCapabilities {
        return super.capabilities(env).copy(
            supportedDeviceTypes = listOf(DEVICE_TYPE_PHYSICAL),
            supportsSelectiveApiAllocation = true,
            features = listOf("inventory", "lease", "release", "lease-scoped-adb-proxy", "per-device-locks"),
            metadata = mapOf(
                "controlPlaneAuth" to if (env.authEnabled) "bearer" else "none",
                "apiSelectionMode" to "per-device",
                "accessIsolation" to "lease-scoped-proxy"
            )
        )
    }
}
