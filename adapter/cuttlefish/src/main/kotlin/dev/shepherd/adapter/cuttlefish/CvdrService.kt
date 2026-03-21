package dev.shepherd.adapter.cuttlefish

import dev.shepherd.adapter.api.runCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps the CVDR (Cloud Virtual Device Runner) CLI to manage Cuttlefish instances.
 *
 * Each lease maps to a CVDR "group" (a set of Cuttlefish instances).
 */
class CvdrService(
    private val cvdrPath: String = "cvdr",
    private val commandTimeoutSeconds: Long = 120,
    leaseStore: CvdrLeaseStore = InMemoryCvdrLeaseStore()
) {
    private val supportedApiLevels: List<String> = listOf("30", "31", "32", "33", "34", "35")
    private val logger = LoggerFactory.getLogger(CvdrService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** lease ID -> CVDR group name */
    private val activeLeases = ConcurrentHashMap<String, CvdrLease>(leaseStore.loadLeases())
    private val persistentLeaseStore = leaseStore

    suspend fun createInstances(
        count: Int,
        apiLevel: String,
        ttlSeconds: Long
    ): CvdrAcquireResult = withContext(Dispatchers.IO) {
        val buildTarget = mapApiLevelToTarget(apiLevel)
        val leaseId = "cf_${UUID.randomUUID().toString().take(8)}"

        val result = runCommand(
            cvdrPath, "create",
            "--build_target", buildTarget,
            "--num_instances", count.toString(),
            timeoutSeconds = commandTimeoutSeconds
        )

        if (!result.isSuccess) {
            logger.error("cvdr create failed (exit ${result.exitCode}): ${result.output}")
            return@withContext CvdrAcquireResult(leaseId = "", acquiredCount = 0, group = "")
        }

        val group = parseGroupFromCreateOutput(result.output)
        if (group.isNullOrBlank()) {
            logger.error("Failed to parse CVDR group from output: ${result.output}")
            return@withContext CvdrAcquireResult(leaseId = "", acquiredCount = 0, group = "")
        }

        activeLeases[leaseId] = CvdrLease(group = group, count = count)
        persistActiveLeases()
        logger.info("Created $count Cuttlefish instance(s) in group '$group', lease=$leaseId")

        CvdrAcquireResult(leaseId = leaseId, acquiredCount = count, group = group)
    }

    suspend fun stopInstances(leaseId: String): Boolean = withContext(Dispatchers.IO) {
        val lease = activeLeases[leaseId]
        if (lease == null) {
            logger.warn("Unknown lease $leaseId — nothing to stop")
            return@withContext true
        }

        val result = runCommand(cvdrPath, "stop", lease.group, timeoutSeconds = commandTimeoutSeconds)
        if (!result.isSuccess) {
            logger.error("cvdr stop '${lease.group}' failed (exit ${result.exitCode}): ${result.output}")
            return@withContext false
        }

        activeLeases.remove(leaseId)
        persistActiveLeases()
        logger.info("Stopped CVDR group '${lease.group}' for lease $leaseId")
        true
    }

    suspend fun listRunningInstances(): Int = withContext(Dispatchers.IO) {
        val result = runCommand(cvdrPath, "list", "--format=json", timeoutSeconds = commandTimeoutSeconds)
        if (!result.isSuccess) {
            logger.warn("cvdr list failed: ${result.output}")
            return@withContext 0
        }
        parseInstanceCount(result.output)
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        runCommand(cvdrPath, "list", timeoutSeconds = commandTimeoutSeconds).isSuccess
    }

    fun activeLeaseCount(): Int = activeLeases.values.sumOf { it.count }

    fun supportedApiLevels(): List<String> = supportedApiLevels

    private fun persistActiveLeases() {
        persistentLeaseStore.saveLeases(activeLeases.toMap())
    }

    private fun mapApiLevelToTarget(apiLevel: String): String {
        return when (apiLevel) {
            "35" -> "aosp_cf_x86_64_phone-trunk_staging-userdebug"
            "34" -> "aosp_cf_x86_64_phone-android14-userdebug"
            "33" -> "aosp_cf_x86_64_phone-android13-userdebug"
            "32", "31" -> "aosp_cf_x86_64_phone-android12-userdebug"
            "30" -> "aosp_cf_x86_64_phone-android11-userdebug"
            else -> "aosp_cf_x86_64_phone-trunk_staging-userdebug"
        }
    }

    private fun parseGroupFromCreateOutput(output: String): String? {
        val groupLine = output.lines().firstOrNull { it.contains("group") }
        return groupLine?.substringAfter(":")?.trim()
    }

    private fun parseInstanceCount(jsonOutput: String): Int {
        return try {
            json.decodeFromString<List<CvdrInstance>>(jsonOutput).size
        } catch (e: Exception) {
            logger.warn("Failed to parse cvdr list output: ${e.message}")
            0
        }
    }
}

data class CvdrLease(val group: String, val count: Int)

data class CvdrAcquireResult(val leaseId: String, val acquiredCount: Int, val group: String)

@Serializable
private data class CvdrInstance(val name: String = "", val status: String = "")
