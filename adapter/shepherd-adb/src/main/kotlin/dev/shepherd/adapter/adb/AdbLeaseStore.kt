package dev.shepherd.adapter.adb

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

interface AdbLeaseStore {
    fun loadLeases(): Map<String, AdbLease>
    fun saveLeases(leases: Map<String, AdbLease>)
}

class InMemoryAdbLeaseStore : AdbLeaseStore {
    private var leases: Map<String, AdbLease> = emptyMap()

    override fun loadLeases(): Map<String, AdbLease> = leases

    override fun saveLeases(leases: Map<String, AdbLease>) {
        this.leases = leases.toMap()
    }
}

class FileAdbLeaseStore(
    private val stateFile: File,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) : AdbLeaseStore {
    private val logger = LoggerFactory.getLogger(FileAdbLeaseStore::class.java)

    override fun loadLeases(): Map<String, AdbLease> {
        if (!stateFile.exists()) {
            return emptyMap()
        }
        return try {
            json.decodeFromString<List<StoredAdbLease>>(stateFile.readText())
                .associate { storedLease ->
                    storedLease.leaseId to AdbLease(
                        leaseId = storedLease.leaseId,
                        devices = storedLease.devices.map { storedDevice ->
                            AdbLeasedDevice(
                                serial = storedDevice.serial,
                                proxyPort = storedDevice.proxyPort,
                                apiLevel = storedDevice.apiLevel,
                                manufacturer = storedDevice.manufacturer,
                                model = storedDevice.model,
                                abi = storedDevice.abi
                            )
                        }
                    )
                }
        } catch (e: Exception) {
            logger.warn("Failed to load persisted ADB leases from ${stateFile.absolutePath}: ${e.message}")
            emptyMap()
        }
    }

    override fun saveLeases(leases: Map<String, AdbLease>) {
        stateFile.parentFile?.mkdirs()
        val serializedLeases: List<StoredAdbLease> = leases.entries
            .sortedBy { entry -> entry.key }
            .map { entry ->
                StoredAdbLease(
                    leaseId = entry.key,
                    devices = entry.value.devices
                        .sortedBy { device -> device.serial }
                        .map { device ->
                            StoredAdbLeasedDevice(
                                serial = device.serial,
                                proxyPort = device.proxyPort,
                                apiLevel = device.apiLevel,
                                manufacturer = device.manufacturer,
                                model = device.model,
                                abi = device.abi
                            )
                        }
                )
            }
        stateFile.writeText(json.encodeToString(serializedLeases))
    }
}

data class AdbLease(
    val leaseId: String,
    val devices: List<AdbLeasedDevice>
)

data class AdbLeasedDevice(
    val serial: String,
    val proxyPort: Int,
    val apiLevel: String?,
    val manufacturer: String?,
    val model: String?,
    val abi: String?
)

@Serializable
private data class StoredAdbLease(
    val leaseId: String,
    val devices: List<StoredAdbLeasedDevice>
)

@Serializable
private data class StoredAdbLeasedDevice(
    val serial: String,
    val proxyPort: Int,
    val apiLevel: String?,
    val manufacturer: String?,
    val model: String?,
    val abi: String?
)
