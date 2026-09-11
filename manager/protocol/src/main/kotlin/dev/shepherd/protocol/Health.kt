package dev.shepherd.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ProviderHealthSummary(
    val name: String,
    val status: String,
    val available: Int? = null,
    val busy: Int? = null,
    val total: Int? = null,
    /** Why the provider is unhealthy; absent when it is healthy. */
    val error: String? = null
)

@Serializable
data class SessionCounts(
    val pending: Int,
    val ready: Int,
    val allocatedDevices: Int
)

@Serializable
data class ShepherdHealthResponse(
    val status: String,
    val version: String,
    val providersTotal: Int,
    val providersHealthy: Int,
    val providers: List<ProviderHealthSummary>,
    val sessions: SessionCounts? = null,
    /** When the provider snapshot behind this answer was taken (ISO-8601). */
    val checkedAt: String? = null
)

@Serializable
data class ShepherdLivenessResponse(val status: String, val version: String)

@Serializable
data class ReadinessResponse(
    val status: String,
    val version: String,
    val checks: Map<String, String>
)
