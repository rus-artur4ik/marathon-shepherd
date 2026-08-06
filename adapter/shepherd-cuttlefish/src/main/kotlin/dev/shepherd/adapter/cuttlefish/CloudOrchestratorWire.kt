package dev.shepherd.adapter.cuttlefish

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/**
 * Wire model for the Cloud Orchestrator REST API, plus the small JSON helpers used to
 * sniff which of its two dialects a response belongs to.
 *
 * Kept separate from `CloudOrchestratorService` because it is a description of somebody
 * else's protocol, not behaviour of ours: it changes when the orchestrator changes, and
 * for no other reason.
 */

/**
 * Which API dialect the orchestrator speaks.
 *
 * Older on-premise builds expose a flat host API; newer ones expose the zoned `/v1/zones`
 * API. The service probes for one at startup and caches the answer.
 */
internal enum class CloudOrchestratorApiMode {
    LEGACY_HOST_API,
    CLOUD_V1
}

/** A host in the zoned API, and the paths derived from it. */
internal data class CloudHostRef(
    val zone: String,
    val host: String
) {
    val hostPath: String = "/v1/zones/$zone/hosts/$host"

    fun groupDeletePath(group: String): String = "$hostPath/groups/$group"

    fun hostOperationWaitPath(operation: String): String = "$hostPath/operations/$operation/:wait"
}

@Serializable
internal data class CreateCvdsRequest(
    val cvd: CreateCvdPayload,
    @SerialName("additional_instances_num")
    val additionalInstancesNum: Int,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
internal data class CreateCvdPayload(
    @SerialName("build_source")
    val buildSource: BuildSourcePayload
)

@Serializable
internal data class BuildSourcePayload(
    @SerialName("system_build_source")
    val systemBuildSource: SystemBuildSourcePayload
)

@Serializable
internal data class SystemBuildSourcePayload(
    @SerialName("build_target")
    val buildTarget: String
)

@Serializable
internal data class CloudOrchestratorDevice(
    val name: String = "",
    val group: String? = null,
    @SerialName("group_name")
    val groupName: String? = null,
    val status: String? = null,
    val state: String? = null
) {
    /**
     * Treats a device with no state field at all as running: some orchestrator builds omit
     * it for healthy instances, and refusing those would make the whole pool invisible.
     */
    fun isRunning(): Boolean {
        val normalizedState: String = (status ?: state).orEmpty().lowercase()
        return normalizedState.isBlank() || normalizedState in setOf("running", "active", "ready", "done")
    }
}

@Serializable
internal data class CreateHostRequest(
    @SerialName("host_instance")
    val hostInstance: JsonObject = buildJsonObject { }
)

internal fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.stringValue(key: String): String? {
    val value: JsonElement = this[key] ?: return null
    return (value as? JsonPrimitive)?.content
}

/**
 * Whether a response is a long-running-operation envelope rather than the resource itself.
 *
 * The orchestrator returns either shape from the same endpoints depending on version and
 * on whether the work completed synchronously, so callers have to sniff.
 */
internal fun JsonObject.looksLikeOperationResponse(): Boolean =
    this["done"]?.let { element -> (element as? JsonPrimitive)?.booleanOrNull } != null &&
        stringValue("name") != null
