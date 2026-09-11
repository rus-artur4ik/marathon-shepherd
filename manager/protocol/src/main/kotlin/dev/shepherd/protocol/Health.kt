package dev.shepherd.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ProviderHealthSummary(val name: String, val status: String)

@Serializable
data class ShepherdHealthResponse(
    val status: String,
    val version: String,
    val providersTotal: Int,
    val providersHealthy: Int,
    val providers: List<ProviderHealthSummary>
)

@Serializable
data class ShepherdLivenessResponse(val status: String, val version: String)
