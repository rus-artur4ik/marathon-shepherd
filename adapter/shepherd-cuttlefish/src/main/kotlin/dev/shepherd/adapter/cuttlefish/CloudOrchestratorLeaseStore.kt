package dev.shepherd.adapter.cuttlefish

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

interface CloudOrchestratorLeaseStore {
    fun loadLeases(): Map<String, CloudOrchestratorLease>
    fun saveLeases(leases: Map<String, CloudOrchestratorLease>)
}

class InMemoryCloudOrchestratorLeaseStore : CloudOrchestratorLeaseStore {
    private var leases: Map<String, CloudOrchestratorLease> = emptyMap()

    override fun loadLeases(): Map<String, CloudOrchestratorLease> = leases

    override fun saveLeases(leases: Map<String, CloudOrchestratorLease>) {
        this.leases = leases.toMap()
    }
}

class FileCloudOrchestratorLeaseStore(
    private val stateFile: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
) : CloudOrchestratorLeaseStore {
    private val logger = LoggerFactory.getLogger(FileCloudOrchestratorLeaseStore::class.java)

    override fun loadLeases(): Map<String, CloudOrchestratorLease> {
        if (!stateFile.exists()) {
            return emptyMap()
        }
        return try {
            json.decodeFromString<List<StoredCloudOrchestratorLease>>(stateFile.readText())
                .associate { storedLease ->
                    storedLease.leaseId to CloudOrchestratorLease(
                        group = storedLease.group,
                        count = storedLease.count,
                        releasePath = storedLease.releasePath.orEmpty()
                    )
                }
        } catch (error: Exception) {
            logger.warn(
                "Failed to load persisted Cuttlefish leases from ${stateFile.absolutePath}: ${error.message}"
            )
            emptyMap()
        }
    }

    override fun saveLeases(leases: Map<String, CloudOrchestratorLease>) {
        stateFile.parentFile?.mkdirs()
        val serializedLeases: List<StoredCloudOrchestratorLease> = leases.entries
            .sortedBy { entry -> entry.key }
            .map { entry ->
                StoredCloudOrchestratorLease(
                    leaseId = entry.key,
                    group = entry.value.group,
                    count = entry.value.count,
                    releasePath = entry.value.releasePath.ifBlank { null }
                )
            }
        stateFile.writeText(json.encodeToString(serializedLeases))
    }
}

@Serializable
private data class StoredCloudOrchestratorLease(
    val leaseId: String,
    val group: String,
    val count: Int,
    val releasePath: String? = null
)
