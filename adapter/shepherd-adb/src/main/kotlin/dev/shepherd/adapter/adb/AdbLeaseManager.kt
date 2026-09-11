package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

data class AdbLeaseAcquisition(
    val leaseId: String?,
    val acquiredDevices: List<AdbLeasedDevice>,
    val busySerials: Map<String, String>,
    val missingSerials: List<String>
)

data class AdbLeaseSnapshot(
    val availableDevices: List<AdbPhysicalDevice>,
    val busyDevices: List<AdbPhysicalDevice>,
    val busySerialsByLease: Map<String, String>,
    /** Every active lease, including those whose devices are no longer attached. */
    val leases: List<AdbLease> = emptyList()
)

class AdbLeaseManager(
    leaseStore: AdbLeaseStore,
    private val proxyController: AdbProxyController
) {
    private val logger = LoggerFactory.getLogger(AdbLeaseManager::class.java)
    private val mutex = Mutex()
    private val persistentLeaseStore = leaseStore
    private val activeLeases: MutableMap<String, AdbLease> = leaseStore.loadLeases().toMutableMap()

    suspend fun restorePersistedLeases(connectedDevices: List<AdbPhysicalDevice>) {
        mutex.withLock {
            if (activeLeases.isEmpty()) {
                return
            }
            val devicesBySerial: Map<String, AdbPhysicalDevice> = connectedDevices.associateBy { device -> device.serial }
            val restoredLeases = mutableMapOf<String, AdbLease>()
            activeLeases.values.forEach { lease ->
                val restoredDevices = mutableListOf<AdbLeasedDevice>()
                lease.devices.forEach { leasedDevice ->
                    val device: AdbPhysicalDevice = devicesBySerial[leasedDevice.serial] ?: run {
                        logger.warn(
                            "Dropping persisted adb lease {} for missing serial {}",
                            lease.leaseId,
                            leasedDevice.serial
                        )
                        return@forEach
                    }
                    val restoredPort = try {
                        proxyController.startDeviceProxy(
                            leaseId = lease.leaseId,
                            device = device,
                            preferredPort = leasedDevice.proxyPort
                        )
                    } catch (error: Exception) {
                        logger.warn(
                            "Failed to restore adb proxy for lease={} serial={} port={}: {}",
                            lease.leaseId,
                            leasedDevice.serial,
                            leasedDevice.proxyPort,
                            error.message
                        )
                        return@forEach
                    }
                    restoredDevices += leasedDevice.copy(proxyPort = restoredPort)
                }
                if (restoredDevices.isNotEmpty()) {
                    restoredLeases[lease.leaseId] = lease.copy(devices = restoredDevices)
                }
            }
            activeLeases.clear()
            activeLeases.putAll(restoredLeases)
            persistLeases()
        }
    }

    suspend fun buildSnapshot(connectedDevices: List<AdbPhysicalDevice>): AdbLeaseSnapshot {
        return mutex.withLock {
            val busyBySerial: Map<String, String> = activeLeases.values
                .flatMap { lease -> lease.devices.map { device -> device.serial to lease.leaseId } }
                .toMap()
            AdbLeaseSnapshot(
                availableDevices = connectedDevices.filterNot { device -> device.serial in busyBySerial },
                busyDevices = connectedDevices.filter { device -> device.serial in busyBySerial },
                busySerialsByLease = busyBySerial,
                leases = activeLeases.values.sortedBy { lease -> lease.leaseId }
            )
        }
    }

    /**
     * Leases up to [requestedCount] free devices on [apiLevel] that also pass [deviceFilter], which
     * is how device selection narrows the choice. [sessionId] is kept with the lease for logs and
     * lease listings.
     */
    suspend fun acquireDevices(
        requestedCount: Int,
        apiLevel: String,
        connectedDevices: List<AdbPhysicalDevice>,
        deviceFilter: (AdbPhysicalDevice) -> Boolean = { true },
        sessionId: String? = null
    ): AdbLeaseAcquisition {
        return mutex.withLock {
            val busyBySerial: Map<String, String> = activeLeases.values
                .flatMap { lease -> lease.devices.map { device -> device.serial to lease.leaseId } }
                .toMap()
            val connectedSerials: Set<String> = connectedDevices.map { device -> device.serial }.toSet()
            val matchingDevices: List<AdbPhysicalDevice> = connectedDevices.filter { device ->
                device.apiLevel == apiLevel && deviceFilter(device)
            }
            val availableDevices: List<AdbPhysicalDevice> = matchingDevices.filterNot { device -> device.serial in busyBySerial }
            val selectedDevices: List<AdbPhysicalDevice> = availableDevices.take(requestedCount)
            val missingSerials: List<String> = activeLeases.values
                .flatMap { lease -> lease.devices.map { device -> device.serial } }
                .filterNot { serial -> serial in connectedSerials }
            if (selectedDevices.isEmpty()) {
                if (busyBySerial.isNotEmpty()) {
                    logger.info(
                        "ADB adapter: no free physical devices for apiLevel={} because busy serials are {}",
                        apiLevel,
                        busyBySerial.entries.joinToString { entry -> "${entry.key}@${entry.value}" }
                    )
                }
                return@withLock AdbLeaseAcquisition(
                    leaseId = null,
                    acquiredDevices = emptyList(),
                    busySerials = busyBySerial.filterKeys { serial -> matchingDevices.any { device -> device.serial == serial } },
                    missingSerials = missingSerials
                )
            }

            val leaseId = "adb_${java.util.UUID.randomUUID().toString().take(8)}"
            val leasedDevices = mutableListOf<AdbLeasedDevice>()
            selectedDevices.forEach { device ->
                val proxyPort = try {
                    proxyController.startDeviceProxy(leaseId = leaseId, device = device)
                } catch (error: Exception) {
                    logger.warn(
                        "ADB adapter: failed to start lease-scoped proxy for serial={} lease={}: {}",
                        device.serial,
                        leaseId,
                        error.message
                    )
                    return@forEach
                }
                leasedDevices += AdbLeasedDevice(
                    serial = device.serial,
                    proxyPort = proxyPort,
                    apiLevel = device.apiLevel,
                    manufacturer = device.manufacturer,
                    model = device.model,
                    abi = device.abi
                )
            }
            if (leasedDevices.isEmpty()) {
                return@withLock AdbLeaseAcquisition(
                    leaseId = null,
                    acquiredDevices = emptyList(),
                    busySerials = busyBySerial.filterKeys { serial -> matchingDevices.any { device -> device.serial == serial } },
                    missingSerials = missingSerials
                )
            }

            activeLeases[leaseId] = AdbLease(leaseId = leaseId, devices = leasedDevices, sessionId = sessionId)
            persistLeases()
            logger.info(
                "Leased adb serials {} as {}{}",
                leasedDevices.joinToString { device -> device.serial },
                leaseId,
                sessionId?.let { id -> " for session $id" }.orEmpty()
            )
            val busyMatchingSerials: Map<String, String> = busyBySerial
                .filterKeys { serial -> matchingDevices.any { device -> device.serial == serial } }
            if (busyMatchingSerials.isNotEmpty()) {
                logger.info(
                    "ADB adapter: skipped busy serials while allocating apiLevel={}: {}",
                    apiLevel,
                    busyMatchingSerials.entries.joinToString { entry -> "${entry.key}@${entry.value}" }
                )
            }
            AdbLeaseAcquisition(
                leaseId = leaseId,
                acquiredDevices = leasedDevices,
                busySerials = busyMatchingSerials,
                missingSerials = missingSerials
            )
        }
    }

    suspend fun releaseLease(leaseId: String): Boolean {
        return mutex.withLock {
            val lease: AdbLease = activeLeases.remove(leaseId) ?: return@withLock true
            proxyController.stopLease(leaseId)
            persistLeases()
            logger.info(
                "Released adb lease {} for serials {}{}",
                leaseId,
                lease.devices.joinToString { device -> device.serial },
                lease.sessionId?.let { id -> " (session $id)" }.orEmpty()
            )
            true
        }
    }

    suspend fun activeLeaseIds(): List<String> {
        return mutex.withLock {
            activeLeases.keys.sorted()
        }
    }

    /** Every active lease, sorted by id. */
    suspend fun listActiveLeases(): List<AdbLease> {
        return mutex.withLock {
            activeLeases.values.sortedBy { lease -> lease.leaseId }
        }
    }

    suspend fun hasLease(leaseId: String): Boolean {
        return mutex.withLock {
            leaseId in activeLeases
        }
    }

    fun buildLeaseAccess(adapterType: String, requestHost: String, leasedDevices: List<AdbLeasedDevice>): AdapterAccess {
        val connections: List<AdapterConnection> = leasedDevices.map { device ->
            AdapterConnection(
                id = leaseConnectionId(adapterType, device.serial),
                protocol = ACCESS_PROTOCOL_ADB,
                transport = ACCESS_TRANSPORT_TCP,
                host = requestHost,
                port = device.proxyPort,
                exposure = ACCESS_EXPOSURE_DIRECT_TCP,
                auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK),
                metadata = mapOf(
                    "scope" to "lease-device",
                    "serial" to device.serial,
                    "managedBy" to "adapter",
                    "isolation" to "lease-scoped-proxy"
                )
            )
        }
        return AdapterAccess(
            preferredConnectionId = connections.firstOrNull()?.id,
            connections = connections,
            metadata = mapOf("adapterType" to adapterType, "accessMode" to "lease-scoped")
        )
    }

    fun buildStatusInventory(
        availableDevices: List<AdbPhysicalDevice>,
        busyDevices: List<AdbPhysicalDevice>,
        busySerialsByLease: Map<String, String>
    ): List<AdapterDeviceProfile> {
        val keys: Set<DeviceProfileKey> = (availableDevices + busyDevices)
            .map { device ->
                DeviceProfileKey(
                    apiLevel = device.apiLevel,
                    manufacturer = device.manufacturer,
                    model = device.model,
                    abi = device.abi
                )
            }
            .toSet()
        return keys.map { key ->
            val matchingAvailableDevices: List<AdbPhysicalDevice> = availableDevices.filter { device ->
                device.toProfileKey() == key
            }
            val matchingBusyDevices: List<AdbPhysicalDevice> = busyDevices.filter { device ->
                device.toProfileKey() == key
            }
            AdapterDeviceProfile(
                deviceType = DEVICE_TYPE_PHYSICAL,
                apiLevel = key.apiLevel,
                manufacturer = key.manufacturer,
                model = key.model,
                abi = key.abi,
                count = matchingAvailableDevices.size,
                metadata = mapOf(
                    "serials" to matchingAvailableDevices.joinToString(",") { device -> device.serial },
                    "busyCount" to matchingBusyDevices.size.toString(),
                    "busySerials" to matchingBusyDevices.joinToString(",") { device -> device.serial },
                    "busyLeases" to matchingBusyDevices.joinToString(",") { device ->
                        "${device.serial}@${busySerialsByLease[device.serial]}"
                    }
                ).filterValues { value -> value.isNotBlank() }
            )
        }.sortedWith(compareBy({ it.apiLevel ?: "" }, { it.manufacturer ?: "" }, { it.model ?: "" }, { it.abi ?: "" }))
    }

    fun buildGroupedProfiles(
        devices: List<AdbPhysicalDevice>,
        busySerialsByLease: Map<String, String> = emptyMap()
    ): List<AdapterDeviceProfile> {
        return devices.groupBy { device ->
            DeviceProfileKey(
                apiLevel = device.apiLevel,
                manufacturer = device.manufacturer,
                model = device.model,
                abi = device.abi
            )
        }.map { (key, groupedDevices) ->
            AdapterDeviceProfile(
                deviceType = DEVICE_TYPE_PHYSICAL,
                apiLevel = key.apiLevel,
                manufacturer = key.manufacturer,
                model = key.model,
                abi = key.abi,
                count = groupedDevices.size,
                metadata = mapOf(
                    "serials" to groupedDevices.joinToString(",") { device -> device.serial },
                    "busySerials" to groupedDevices
                        .mapNotNull { device -> device.serial.takeIf { serial -> serial in busySerialsByLease } }
                        .joinToString(",")
                ).filterValues { value -> value.isNotBlank() }
            )
        }.sortedWith(compareBy({ it.apiLevel ?: "" }, { it.manufacturer ?: "" }, { it.model ?: "" }, { it.abi ?: "" }))
    }

    private fun persistLeases() {
        persistentLeaseStore.saveLeases(activeLeases.toMap())
    }
}

/** Id of the access connection that reaches [serial] inside a lease; `/acquire` reports it per device. */
fun leaseConnectionId(adapterType: String, serial: String): String = "$adapterType-$serial"

private fun AdbPhysicalDevice.toProfileKey(): DeviceProfileKey {
    return DeviceProfileKey(
        apiLevel = apiLevel,
        manufacturer = manufacturer,
        model = model,
        abi = abi
    )
}
