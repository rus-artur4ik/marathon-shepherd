package dev.shepherd.adapter.api

import kotlinx.serialization.Serializable

/**
 * Shared REST contract between the Manager and any Adapter (adb, farm, …).
 *
 * Every adapter exposes the same four endpoints:
 *   GET  /health              → HealthResponse
 *   GET  /status              → PoolStatusResponse
 *   POST /acquire             → AcquireResponse
 *   DELETE /release/{leaseId} → 200 OK
 *
 * The Manager owns session lifecycle state.
 * Adapters may keep the minimal local metadata required to release acquired resources safely.
 */

const val ACCESS_PROTOCOL_ADB: String = "adb"
const val ACCESS_TRANSPORT_TCP: String = "tcp"
const val ACCESS_EXPOSURE_DIRECT_TCP: String = "direct-tcp"
const val ACCESS_AUTH_NETWORK: String = "network"
const val DEVICE_TYPE_PHYSICAL: String = "physical"
const val DEVICE_TYPE_EMULATOR: String = "emulator"

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val adapterType: String
)

@Serializable
data class AdapterPool(
    val available: Int,
    val busy: Int,
    val total: Int
)

@Serializable
data class AdapterConnectionAuth(
    val type: String = ACCESS_AUTH_NETWORK,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class AdapterConnection(
    val id: String,
    val protocol: String,
    val transport: String,
    val host: String,
    val port: Int,
    val exposure: String,
    val auth: AdapterConnectionAuth = AdapterConnectionAuth(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class AdapterAccess(
    val preferredConnectionId: String? = null,
    val connections: List<AdapterConnection> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class AdapterCapabilities(
    val allocationModes: List<String> = emptyList(),
    val supportedProtocols: List<String> = emptyList(),
    val supportedExposureModes: List<String> = emptyList(),
    val supportedDeviceTypes: List<String> = emptyList(),
    val supportedApiLevels: List<String> = emptyList(),
    val supportsSelectiveApiAllocation: Boolean = false,
    val supportsTestAccessAdb: Boolean = true,
    val supportsTestAccessGrpc: Boolean = false,
    val supportsTestAccessConsole: Boolean = false,
    val features: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class AdapterDeviceProfile(
    val deviceType: String,
    val apiLevel: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val abi: String? = null,
    val count: Int,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class PoolStatusResponse(
    val pool: AdapterPool,
    val access: AdapterAccess,
    val inventory: List<AdapterDeviceProfile> = emptyList(),
    val capabilities: AdapterCapabilities = AdapterCapabilities(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class AcquireRequest(
    val count: Int,
    val apiLevel: String,
    val ttlSeconds: Long
)

@Serializable
data class AcquireResponse(
    val leaseId: String,
    val acquiredCount: Int,
    val access: AdapterAccess,
    val inventory: List<AdapterDeviceProfile> = emptyList(),
    val capabilities: AdapterCapabilities = AdapterCapabilities(),
    val metadata: Map<String, String> = emptyMap()
)

fun AdapterAccess.preferredAdbTcpConnection(): AdapterConnection? {
    val preferredConnection: AdapterConnection? = preferredConnectionId
        ?.let { preferredId -> connections.firstOrNull { connection -> connection.id == preferredId } }
    if (preferredConnection != null &&
        preferredConnection.protocol == ACCESS_PROTOCOL_ADB &&
        preferredConnection.transport == ACCESS_TRANSPORT_TCP
    ) {
        return preferredConnection
    }
    return connections.firstOrNull { connection ->
        connection.protocol == ACCESS_PROTOCOL_ADB && connection.transport == ACCESS_TRANSPORT_TCP
    }
}
