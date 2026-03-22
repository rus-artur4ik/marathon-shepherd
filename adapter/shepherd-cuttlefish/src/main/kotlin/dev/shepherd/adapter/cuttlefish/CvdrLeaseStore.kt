package dev.shepherd.adapter.cuttlefish

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

interface CvdrLeaseStore {
    fun loadLeases(): Map<String, CvdrLease>
    fun saveLeases(leases: Map<String, CvdrLease>)
}

class InMemoryCvdrLeaseStore : CvdrLeaseStore {
    private var leases: Map<String, CvdrLease> = emptyMap()

    override fun loadLeases(): Map<String, CvdrLease> = leases

    override fun saveLeases(leases: Map<String, CvdrLease>) {
        this.leases = leases.toMap()
    }
}

class FileCvdrLeaseStore(
    private val stateFile: File,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) : CvdrLeaseStore {
    private val logger = LoggerFactory.getLogger(FileCvdrLeaseStore::class.java)

    override fun loadLeases(): Map<String, CvdrLease> {
        if (!stateFile.exists()) {
            return emptyMap()
        }
        return try {
            json.decodeFromString<List<StoredCvdrLease>>(stateFile.readText())
                .associate { storedLease ->
                    storedLease.leaseId to CvdrLease(group = storedLease.group, count = storedLease.count)
                }
        } catch (e: Exception) {
            logger.warn("Failed to load persisted Cuttlefish leases from ${stateFile.absolutePath}: ${e.message}")
            emptyMap()
        }
    }

    override fun saveLeases(leases: Map<String, CvdrLease>) {
        stateFile.parentFile?.mkdirs()
        val serializedLeases: List<StoredCvdrLease> = leases.entries
            .sortedBy { entry -> entry.key }
            .map { entry ->
                StoredCvdrLease(
                    leaseId = entry.key,
                    group = entry.value.group,
                    count = entry.value.count
                )
            }
        stateFile.writeText(json.encodeToString(serializedLeases))
    }
}

@Serializable
private data class StoredCvdrLease(
    val leaseId: String,
    val group: String,
    val count: Int
)
