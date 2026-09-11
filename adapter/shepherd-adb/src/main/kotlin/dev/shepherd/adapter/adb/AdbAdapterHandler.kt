package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.*

/** Why a leased device that no longer shows up in `adb devices` at all is offline. */
private const val REASON_DISCONNECTED: String = "disconnected"

class AdbAdapterHandler(
    private val adbService: AdbService,
    private val leaseManager: AdbLeaseManager,
    private val labels: AdbDeviceLabels = AdbDeviceLabels()
) : AdapterHandler(adapterType = "adb") {

    override suspend fun isHealthy(): Boolean = adbService.isAdbReachable()

    override suspend fun status(): AdapterStatus {
        val attached: AdbDeviceInventory = adbService.listDeviceInventory()
        val connectedDevices: List<AdbPhysicalDevice> = attached.readyDevices
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
            ).filterValues { value -> value.isNotBlank() },
            devices = statusDevices(attached, snapshot, labels.current())
        )
    }

    override suspend fun acquire(request: AcquireRequest): AcquireResult {
        val connectedDevices: List<AdbPhysicalDevice> = adbService.listPhysicalDevices()
        val acquisition: AdbLeaseAcquisition = leaseManager.acquireDevices(
            requestedCount = request.count,
            apiLevel = request.apiLevel,
            connectedDevices = connectedDevices,
            deviceFilter = selectionFilter(request, labels.current()),
            sessionId = request.sessionId
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
            ).filterValues { value -> value.isNotBlank() },
            devices = acquisition.acquiredDevices.map { device ->
                AdapterLeasedDevice(id = device.serial, connectionId = leaseConnectionId(adapterType, device.serial))
            }
        )
    }

    override suspend fun release(leaseId: String): Boolean = leaseManager.releaseLease(leaseId)

    /** Leases here last until released — there is no TTL to extend — so renewing only confirms the lease still exists. */
    override suspend fun renew(leaseId: String, ttlSeconds: Long): Boolean = leaseManager.hasLease(leaseId)

    override suspend fun leases(): List<AdapterLease> = leaseManager.listActiveLeases().map { lease ->
        AdapterLease(
            leaseId = lease.leaseId,
            deviceIds = lease.devices.map { device -> device.serial }.sorted(),
            sessionId = lease.sessionId
        )
    }

    override fun capabilities(env: AdapterEnv): AdapterCapabilities {
        return super.capabilities(env).copy(
            supportedDeviceTypes = listOf(DEVICE_TYPE_PHYSICAL),
            supportsSelectiveApiAllocation = true,
            features = listOf(
                "inventory",
                "lease",
                "release",
                "lease-scoped-adb-proxy",
                "per-device-locks",
                FEATURE_DEVICE_SELECTION,
                FEATURE_LEASE_RENEW,
                FEATURE_LEASE_LIST
            ),
            metadata = mapOf(
                "controlPlaneAuth" to if (env.authEnabled) "bearer" else "none",
                "apiSelectionMode" to "per-device",
                "accessIsolation" to "lease-scoped-proxy"
            )
        )
    }

    /**
     * What [FEATURE_DEVICE_SELECTION] promises: only [AcquireRequest.deviceIds] when any are given,
     * never [AcquireRequest.excludeDeviceIds], and every one of [AcquireRequest.labels].
     */
    private fun selectionFilter(request: AcquireRequest, labelTable: AdbLabelTable): (AdbPhysicalDevice) -> Boolean {
        val allowedSerials: Set<String> = request.deviceIds.toSet()
        val excludedSerials: Set<String> = request.excludeDeviceIds.toSet()
        return { device ->
            val deviceLabels: Map<String, String> = labelTable.labelsFor(device.serial)
            (allowedSerials.isEmpty() || device.serial in allowedSerials) &&
                device.serial !in excludedSerials &&
                request.labels.all { (key, value) -> deviceLabels[key] == value }
        }
    }

    /**
     * Every device an operator should see: ready devices as available or busy, attached ones that
     * cannot be leased as offline with the reason, and leased serials that vanished from adb as
     * offline and disconnected, still naming the lease that holds them.
     */
    private fun statusDevices(attached: AdbDeviceInventory, snapshot: AdbLeaseSnapshot, labelTable: AdbLabelTable): List<AdapterDevice> {
        val leaseIdBySerial: Map<String, String> = snapshot.busySerialsByLease
        val readyDevices: List<AdapterDevice> = attached.readyDevices.map { device ->
            val leaseId: String? = leaseIdBySerial[device.serial]
            AdapterDevice(
                id = device.serial,
                deviceType = DEVICE_TYPE_PHYSICAL,
                state = if (leaseId == null) DEVICE_STATE_AVAILABLE else DEVICE_STATE_BUSY,
                apiLevel = device.apiLevel,
                manufacturer = device.manufacturer,
                model = device.model,
                abi = device.abi,
                leaseId = leaseId,
                labels = labelTable.labelsFor(device.serial)
            )
        }
        val unavailableDevices: List<AdapterDevice> = attached.unavailableDevices.map { device ->
            AdapterDevice(
                id = device.serial,
                deviceType = DEVICE_TYPE_PHYSICAL,
                state = DEVICE_STATE_OFFLINE,
                apiLevel = device.apiLevel,
                manufacturer = device.manufacturer,
                model = device.model,
                abi = device.abi,
                leaseId = leaseIdBySerial[device.serial],
                labels = labelTable.labelsFor(device.serial),
                metadata = mapOf("reason" to device.reason)
            )
        }
        val attachedSerials: Set<String> = (readyDevices + unavailableDevices).map { device -> device.id }.toSet()
        val disconnectedDevices: List<AdapterDevice> = snapshot.leases.flatMap { lease ->
            lease.devices
                .filter { device -> device.serial !in attachedSerials }
                .map { device ->
                    AdapterDevice(
                        id = device.serial,
                        deviceType = DEVICE_TYPE_PHYSICAL,
                        state = DEVICE_STATE_OFFLINE,
                        apiLevel = device.apiLevel,
                        manufacturer = device.manufacturer,
                        model = device.model,
                        abi = device.abi,
                        leaseId = lease.leaseId,
                        labels = labelTable.labelsFor(device.serial),
                        metadata = mapOf("reason" to REASON_DISCONNECTED)
                    )
                }
        }
        return (readyDevices + unavailableDevices + disconnectedDevices).sortedBy { device -> device.id }
    }
}
