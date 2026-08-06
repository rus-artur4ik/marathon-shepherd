package dev.shepherd.adapter.cuttlefish

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class CloudOrchestratorService(
    private val orchestratorUrl: String,
    private val authToken: String = "",
    private val basicUsername: String = "",
    private val requestTimeoutSeconds: Long = 120,
    private val httpClient: HttpClient = buildCloudOrchestratorHttpClient(),
    leaseStore: CloudOrchestratorLeaseStore = InMemoryCloudOrchestratorLeaseStore()
) {
    private val supportedApiLevels: List<String> = listOf("30", "31", "32", "33", "34", "35")
    private val logger = LoggerFactory.getLogger(CloudOrchestratorService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val activeLeases = ConcurrentHashMap<String, CloudOrchestratorLease>(leaseStore.loadLeases())
    private val persistentLeaseStore: CloudOrchestratorLeaseStore = leaseStore
    private val transport = CloudOrchestratorTransport(
        orchestratorUrl = orchestratorUrl,
        authToken = authToken,
        basicUsername = basicUsername,
        httpClient = httpClient,
        requestTimeoutSeconds = requestTimeoutSeconds
    )

    @Volatile
    private var apiMode: CloudOrchestratorApiMode? = null

    suspend fun createInstances(count: Int, apiLevel: String, ttlSeconds: Long): CloudOrchestratorAcquireResult =
        withContext(Dispatchers.IO) {
            val normalizedCount: Int = count.coerceAtLeast(0)
            if (normalizedCount == 0) {
                return@withContext CloudOrchestratorAcquireResult(leaseId = null, acquiredCount = 0, group = "")
            }
            val leaseId: String = "cf_${UUID.randomUUID().toString().take(8)}"
            val request = CreateCvdsRequest(
                cvd = CreateCvdPayload(
                    buildSource = BuildSourcePayload(
                        systemBuildSource = SystemBuildSourcePayload(
                            buildTarget = mapApiLevelToTarget(apiLevel)
                        )
                    )
                ),
                additionalInstancesNum = normalizedCount - 1,
                metadata = mapOf(
                    "leaseId" to leaseId,
                    "ttlSeconds" to ttlSeconds.toString()
                )
            )
            val requestBody: String = json.encodeToString(CreateCvdsRequest.serializer(), request)
            val mode: CloudOrchestratorApiMode = resolveApiMode()
                ?: return@withContext CloudOrchestratorAcquireResult(leaseId = null, acquiredCount = 0, group = "")
            logger.info(
                "Requesting {} Cuttlefish instance(s) via {} at api={} target={} ttl={}s lease={}",
                normalizedCount,
                mode,
                apiLevel,
                mapApiLevelToTarget(apiLevel),
                ttlSeconds,
                leaseId
            )
            val responseText: String
            val releasePath: String
            when (mode) {
                CloudOrchestratorApiMode.LEGACY_HOST_API -> {
                    responseText = sendRequest(
                        method = "POST",
                        path = "/cvds",
                        requestBody = requestBody
                    ) ?: return@withContext CloudOrchestratorAcquireResult(leaseId = null, acquiredCount = 0, group = "")
                    releasePath = ""
                }
                CloudOrchestratorApiMode.CLOUD_V1 -> {
                    val hostRef: CloudHostRef = ensureCloudHost()
                        ?: return@withContext CloudOrchestratorAcquireResult(leaseId = null, acquiredCount = 0, group = "")
                    logger.info(
                        "Using Cloud Orchestrator host '{}' in zone '{}' for lease {}",
                        hostRef.host,
                        hostRef.zone,
                        leaseId
                    )
                    val createResponseText: String = sendRequest(
                        method = "POST",
                        path = "${hostRef.hostPath}/cvds",
                        requestBody = requestBody
                    ) ?: return@withContext CloudOrchestratorAcquireResult(leaseId = null, acquiredCount = 0, group = "")
                    responseText = waitForHostOperationIfNeeded(
                        hostRef = hostRef,
                        responseText = createResponseText,
                        description = "create Cuttlefish group for lease $leaseId"
                    ) ?: return@withContext CloudOrchestratorAcquireResult(leaseId = null, acquiredCount = 0, group = "")
                    releasePath = hostRef.groupDeletePath(group = "__PENDING__")
                }
            }
            val group: String = parseGroupFromCreateResponse(responseText).orEmpty()
            if (group.isBlank()) {
                logger.error(
                    "Failed to parse Cloud Orchestrator group from response: {}",
                    summarizeResponseBody(responseText)
                )
                return@withContext CloudOrchestratorAcquireResult(leaseId = null, acquiredCount = 0, group = "")
            }
            activeLeases[leaseId] = CloudOrchestratorLease(
                group = group,
                count = normalizedCount,
                releasePath = if (releasePath.isBlank()) "" else releasePath.replace("__PENDING__", group)
            )
            persistActiveLeases()
            logger.info(
                "Created {} Cuttlefish instance(s) in group '{}' through Cloud Orchestrator, lease={}",
                normalizedCount,
                group,
                leaseId
            )
            CloudOrchestratorAcquireResult(
                leaseId = leaseId,
                acquiredCount = normalizedCount,
                group = group
            )
        }

    suspend fun stopInstances(leaseId: String): Boolean = withContext(Dispatchers.IO) {
        val lease: CloudOrchestratorLease? = activeLeases[leaseId]
        if (lease == null) {
            logger.warn("Unknown lease {} — nothing to stop", leaseId)
            return@withContext true
        }
        val responseText: String? = sendRequest(
            method = "DELETE",
            path = lease.releasePath.ifBlank { "/cvds/${lease.group}" }
        )
        if (responseText == null) {
            logger.error("Failed to delete Cloud Orchestrator group '{}' for lease {}", lease.group, leaseId)
            return@withContext false
        }
        activeLeases.remove(leaseId)
        persistActiveLeases()
        logger.info("Deleted Cloud Orchestrator group '{}' for lease {}", lease.group, leaseId)
        true
    }

    suspend fun listRunningInstances(): Int = withContext(Dispatchers.IO) {
        when (resolveApiMode()) {
            CloudOrchestratorApiMode.LEGACY_HOST_API -> listLegacyRunningDevices().size
            CloudOrchestratorApiMode.CLOUD_V1 -> listCloudRunningDevices().size
            null -> 0
        }
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        resolveApiMode() != null
    }

    fun activeLeaseCount(): Int = activeLeases.values.sumOf { lease -> lease.count }

    fun supportedApiLevels(): List<String> = supportedApiLevels

    private fun persistActiveLeases() {
        persistentLeaseStore.saveLeases(activeLeases.toMap())
    }

    private fun listLegacyRunningDevices(): List<CloudOrchestratorDevice> {
        val responseText: String = sendRequest(method = "GET", path = "/devices") ?: return emptyList()
        return parseRunningDevices(responseText)
    }

    private suspend fun listCloudRunningDevices(): List<CloudOrchestratorDevice> {
        val zone: String = selectZone(listZones()) ?: return emptyList()
        return listHosts(zone)
            .flatMap { host -> listHostCvds(host) }
            .filter { device -> device.isRunning() }
    }

    private fun parseRunningDevices(responseText: String): List<CloudOrchestratorDevice> {
        val payload: JsonElement = parseJsonPayload(responseText, "device list") ?: return emptyList()
        val devices: List<CloudOrchestratorDevice> = when (payload) {
            is JsonArray -> payload.mapNotNull(::decodeDeviceOrNull)
            is JsonObject -> payload["devices"]?.let { devicesElement ->
                if (devicesElement is JsonArray) {
                    devicesElement.mapNotNull(::decodeDeviceOrNull)
                } else {
                    emptyList()
                }
            } ?: emptyList()
            else -> emptyList()
        }
        return devices.filter { device -> device.isRunning() }
    }

    private fun decodeDeviceOrNull(payload: JsonElement): CloudOrchestratorDevice? {
        return try {
            json.decodeFromJsonElement(CloudOrchestratorDevice.serializer(), payload)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGroupFromCreateResponse(responseText: String): String? {
        val payload: JsonElement = parseJsonPayload(responseText, "create response") ?: return null
        if (payload !is JsonObject) {
            return null
        }
        return payload.stringValue("name")
            ?: payload.stringValue("group")
            ?: payload.stringValue("groupName")
            ?: payload.stringValue("cvd_group")
            ?: payload["cvds"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()?.stringValue("group_name")
            ?: payload["cvds"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()?.stringValue("group")
    }

    private fun parseHostName(responseText: String): String? {
        val payload: JsonElement = parseJsonPayload(responseText, "host response") ?: return null
        if (payload !is JsonObject) {
            return null
        }
        return payload.stringValue("name")
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

    private suspend fun ensureCloudHost(): CloudHostRef? {
        val zone: String = selectZone(listZones()) ?: return null
        val existingHosts: List<CloudHostRef> = listHosts(zone)
        if (existingHosts.isNotEmpty()) {
            val existingHost: CloudHostRef = existingHosts.first()
            logger.info("Reusing existing Cloud Orchestrator host '{}' in zone '{}'", existingHost.host, existingHost.zone)
            return if (waitForHostProxyReady(existingHost)) existingHost else null
        }
        val createdHost: CloudHostRef = createHost(zone) ?: return null
        return if (waitForHostProxyReady(createdHost)) createdHost else null
    }

    private fun resolveApiMode(): CloudOrchestratorApiMode? {
        apiMode?.let { cachedMode -> return cachedMode }
        if (sendRequest(method = "GET", path = "/devices", logFailures = false) != null ||
            sendRequest(method = "GET", path = "/health", logFailures = false) != null
        ) {
            apiMode = CloudOrchestratorApiMode.LEGACY_HOST_API
            logger.info("Cloud Orchestrator API mode detected: {}", apiMode)
            return apiMode
        }
        val zones: List<String> = listZones(logFailures = false)
        if (zones.isNotEmpty()) {
            apiMode = CloudOrchestratorApiMode.CLOUD_V1
            logger.info("Cloud Orchestrator API mode detected: {}", apiMode)
            return apiMode
        }
        return null
    }

    private fun listZones(logFailures: Boolean = true): List<String> {
        val responseText: String = sendRequest(
            method = "GET",
            path = "/v1/zones",
            logFailures = logFailures
        ) ?: return emptyList()
        val payload: JsonObject = parseJsonObject(responseText, "zones list") ?: return emptyList()
        return payload["items"]
            ?.jsonArrayOrNull()
            ?.mapNotNull { zone -> zone.jsonObjectOrNull()?.stringValue("name") }
            .orEmpty()
    }

    private fun selectZone(zones: List<String>): String? {
        return zones.firstOrNull { zone -> zone == DEFAULT_CLOUD_ZONE } ?: zones.firstOrNull()
    }

    private fun listHosts(zone: String): List<CloudHostRef> {
        val responseText: String = sendRequest(
            method = "GET",
            path = "/v1/zones/$zone/hosts"
        ) ?: return emptyList()
        val payload: JsonObject = parseJsonObject(responseText, "hosts list") ?: return emptyList()
        return payload["items"]
            ?.jsonArrayOrNull()
            ?.mapNotNull { host ->
                val hostName: String = host.jsonObjectOrNull()?.stringValue("name").orEmpty()
                hostName.takeIf { name -> name.isNotBlank() }?.let { name -> CloudHostRef(zone = zone, host = name) }
            }
            .orEmpty()
    }

    private fun listHostCvds(hostRef: CloudHostRef): List<CloudOrchestratorDevice> {
        val responseText: String = sendRequest(method = "GET", path = "${hostRef.hostPath}/cvds") ?: return emptyList()
        val payload: JsonObject = parseJsonObject(responseText, "host cvds") ?: return emptyList()
        return payload["cvds"]
            ?.jsonArrayOrNull()
            ?.mapNotNull(::decodeDeviceOrNull)
            .orEmpty()
    }

    private fun parseJsonObject(responseText: String, label: String): JsonObject? {
        return parseJsonPayload(responseText, label) as? JsonObject
    }

    private fun sendRequest(method: String, path: String, requestBody: String? = null, logFailures: Boolean = true): String? =
        transport.send(method, path, requestBody, logFailures)

    private fun executeRequest(method: String, path: String, requestBody: String? = null): CloudHttpResponse =
        transport.execute(method, path, requestBody)

    private suspend fun createHost(zone: String): CloudHostRef? {
        logger.info("Creating Cloud Orchestrator host in zone '{}'", zone)
        val requestBody: String = json.encodeToString(CreateHostRequest.serializer(), CreateHostRequest())
        val createResponseText: String = sendRequest(
            method = "POST",
            path = "/v1/zones/$zone/hosts",
            requestBody = requestBody
        ) ?: return null
        val resolvedResponseText: String = waitForZoneOperationIfNeeded(
            zone = zone,
            responseText = createResponseText,
            description = "create host in zone '$zone'"
        ) ?: return null
        val hostName: String = parseHostName(resolvedResponseText).orEmpty()
        if (hostName.isNotBlank()) {
            return CloudHostRef(zone = zone, host = hostName)
        }

        repeat(CLOUD_HOST_READY_RETRIES) {
            delay(CLOUD_HOST_READY_RETRY_DELAY_MS)
            val hosts: List<CloudHostRef> = listHosts(zone)
            if (hosts.isNotEmpty()) {
                return hosts.first()
            }
        }
        logger.error(
            "Cloud Orchestrator did not return any host after createHost in zone '{}'; response={}",
            zone,
            summarizeResponseBody(resolvedResponseText)
        )
        return null
    }

    private suspend fun waitForZoneOperationIfNeeded(zone: String, responseText: String, description: String): String? {
        val operationName: String = parseOperationName(responseText) ?: return responseText
        return waitForOperation(
            path = "/v1/zones/$zone/operations/$operationName/:wait",
            description = description
        )
    }

    private suspend fun waitForHostOperationIfNeeded(hostRef: CloudHostRef, responseText: String, description: String): String? {
        val operationName: String = parseOperationName(responseText) ?: return responseText
        return waitForOperation(
            path = hostRef.hostOperationWaitPath(operationName),
            description = description
        )
    }

    private suspend fun waitForOperation(path: String, description: String): String? {
        repeat(CLOUD_OPERATION_WAIT_RETRIES) { attempt ->
            val response: CloudHttpResponse = try {
                executeRequest(method = "POST", path = path, requestBody = "{}")
            } catch (error: Exception) {
                logger.warn(
                    "Cloud Orchestrator wait '{}' attempt {} error: {}",
                    description,
                    attempt + 1,
                    error.message
                )
                delay(CLOUD_OPERATION_WAIT_RETRY_DELAY_MS)
                return@repeat
            }
            when {
                response.statusCode in 200..299 -> {
                    if (attempt > 0) {
                        logger.info(
                            "Cloud Orchestrator wait '{}' completed after {} attempt(s)",
                            description,
                            attempt + 1
                        )
                    }
                    return response.body
                }
                response.statusCode == 503 -> {
                    if (attempt == 0 || (attempt + 1) % 10 == 0) {
                        logger.info(
                            "Cloud Orchestrator wait '{}' still pending after {} attempt(s)",
                            description,
                            attempt + 1
                        )
                    }
                    delay(CLOUD_OPERATION_WAIT_RETRY_DELAY_MS)
                }
                else -> {
                    logger.error(
                        "Cloud Orchestrator wait '{}' failed with {}: {}",
                        description,
                        response.statusCode,
                        summarizeResponseBody(response.body)
                    )
                    return null
                }
            }
        }
        logger.error("Cloud Orchestrator wait '{}' timed out", description)
        return null
    }

    private suspend fun waitForHostProxyReady(hostRef: CloudHostRef): Boolean {
        repeat(CLOUD_HOST_PROXY_READY_RETRIES) { attempt ->
            val response: CloudHttpResponse = try {
                executeRequest(method = "GET", path = "${hostRef.hostPath}/cvds")
            } catch (error: Exception) {
                logger.warn(
                    "Cloud Orchestrator host proxy '{}' readiness attempt {} error: {}",
                    hostRef.host,
                    attempt + 1,
                    error.message
                )
                delay(CLOUD_HOST_PROXY_READY_RETRY_DELAY_MS)
                return@repeat
            }
            if (response.statusCode in 200..299) {
                if (attempt > 0) {
                    logger.info(
                        "Cloud Orchestrator host proxy '{}' became ready after {} attempt(s)",
                        hostRef.host,
                        attempt + 1
                    )
                }
                return true
            }
            if (response.statusCode !in setOf(404, 502, 503)) {
                logger.warn(
                    "Cloud Orchestrator host proxy '{}' readiness returned {}: {}",
                    hostRef.host,
                    response.statusCode,
                    summarizeResponseBody(response.body)
                )
            }
            delay(CLOUD_HOST_PROXY_READY_RETRY_DELAY_MS)
        }
        logger.error(
            "Cloud Orchestrator host proxy '{}' did not become ready via {}",
            hostRef.host,
            hostRef.hostPath
        )
        return false
    }

    private fun parseOperationName(responseText: String): String? {
        val payload: JsonElement = parseJsonPayload(responseText, "operation response") ?: return null
        if (payload !is JsonObject || !payload.looksLikeOperationResponse()) {
            return null
        }
        return payload.stringValue("name")
    }

    private fun parseJsonPayload(responseText: String, label: String): JsonElement? {
        val parsedPayload: JsonElement = try {
            json.parseToJsonElement(responseText)
        } catch (error: Exception) {
            logger.warn("Failed to parse Cloud Orchestrator {}: {}", label, error.message)
            return null
        }
        if (parsedPayload is JsonObject) {
            val nestedResponse: String? = parsedPayload.stringValue("response")
            if (!nestedResponse.isNullOrBlank()) {
                return try {
                    json.parseToJsonElement(nestedResponse)
                } catch (error: Exception) {
                    logger.warn("Failed to parse nested Cloud Orchestrator {} response: {}", label, error.message)
                    parsedPayload
                }
            }
        }
        return parsedPayload
    }
}

/** A lease over a group of Cuttlefish instances, persisted across adapter restarts. */
data class CloudOrchestratorLease(
    val group: String,
    val count: Int,
    val releasePath: String = ""
)

/** Outcome of an acquire attempt; [leaseId] is null exactly when [acquiredCount] is zero. */
data class CloudOrchestratorAcquireResult(val leaseId: String?, val acquiredCount: Int, val group: String)

private const val DEFAULT_CLOUD_ZONE: String = "local"
private const val CLOUD_HOST_READY_RETRIES: Int = 10
private const val CLOUD_HOST_READY_RETRY_DELAY_MS: Long = 1_000L
private const val CLOUD_OPERATION_WAIT_RETRIES: Int = 24
private const val CLOUD_OPERATION_WAIT_RETRY_DELAY_MS: Long = 5_000L
private const val CLOUD_HOST_PROXY_READY_RETRIES: Int = 24
private const val CLOUD_HOST_PROXY_READY_RETRY_DELAY_MS: Long = 5_000L
