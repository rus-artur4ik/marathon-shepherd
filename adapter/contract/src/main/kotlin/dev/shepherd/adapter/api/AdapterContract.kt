package dev.shepherd.adapter.api

import kotlinx.serialization.Serializable

/**
 * Shared REST contract between the Manager and any Adapter (adb, farm, …).
 *
 * Every adapter exposes the same endpoints:
 *   GET  /health                  → HealthResponse
 *   GET  /status                  → PoolStatusResponse
 *   POST /acquire                 → AcquireResponse
 *   DELETE /release/{leaseId}     → 200 OK
 *   POST /leases/{leaseId}/renew  → 200 OK, or 501 when the adapter cannot renew
 *   GET  /leases                  → LeasesResponse, or 501 when it does not track them
 *   GET  /metrics                 → Prometheus text
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

/** Values of [AdapterDevice.state]. */
const val DEVICE_STATE_AVAILABLE: String = "available"
const val DEVICE_STATE_BUSY: String = "busy"

/** Attached but unusable: unauthorized, offline, still booting, or leased but disconnected. */
const val DEVICE_STATE_OFFLINE: String = "offline"

/**
 * Optional features an adapter declares in [AdapterCapabilities.features]. The manager uses
 * a feature only when the adapter declares it, so older adapters keep working unchanged.
 */

/** `/acquire` honours [AcquireRequest.deviceIds], [AcquireRequest.excludeDeviceIds] and [AcquireRequest.labels]. */
const val FEATURE_DEVICE_SELECTION: String = "device-selection"

/** `POST /leases/{leaseId}/renew` extends a lease. */
const val FEATURE_LEASE_RENEW: String = "lease-renew"

/** `GET /leases` lists every lease the adapter holds. */
const val FEATURE_LEASE_LIST: String = "lease-list"

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

/**
 * One device as the adapter sees it. Adapters that can tell devices apart report them in
 * [PoolStatusResponse.devices]; pool-only adapters leave the list empty.
 */
@Serializable
data class AdapterDevice(
    /** Stable within the adapter, e.g. an adb serial or an instance name. */
    val id: String,
    val deviceType: String,
    /** [DEVICE_STATE_AVAILABLE], [DEVICE_STATE_BUSY] or [DEVICE_STATE_OFFLINE]. */
    val state: String,
    val apiLevel: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val abi: String? = null,
    /** The lease holding the device, when it is busy. */
    val leaseId: String? = null,
    /** Operator-assigned labels that sessions can select on, e.g. `form=tablet`. */
    val labels: Map<String, String> = emptyMap(),
    /** Free-form details, e.g. why a device is offline. */
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class PoolStatusResponse(
    val pool: AdapterPool,
    val access: AdapterAccess,
    val inventory: List<AdapterDeviceProfile> = emptyList(),
    val capabilities: AdapterCapabilities = AdapterCapabilities(),
    val metadata: Map<String, String> = emptyMap(),
    val devices: List<AdapterDevice> = emptyList()
)

/**
 * The selection fields are honoured only by adapters declaring [FEATURE_DEVICE_SELECTION].
 * The manager omits them when empty, so the body stays readable by older adapters.
 */
@Serializable
data class AcquireRequest(
    val count: Int,
    val apiLevel: String,
    val ttlSeconds: Long,
    /** Choose only among these device ids. Empty means any matching device. */
    val deviceIds: List<String> = emptyList(),
    /** Never choose these device ids, e.g. devices in maintenance. */
    val excludeDeviceIds: List<String> = emptyList(),
    /** Every chosen device must carry all of these labels. */
    val labels: Map<String, String> = emptyMap(),
    /** The manager session the lease is for, for adapter-side logs and lease listings. */
    val sessionId: String? = null
)

/** A device inside a lease, with the access connection that reaches it. */
@Serializable
data class AdapterLeasedDevice(
    val id: String,
    /** Id of the connection in [AcquireResponse.access] that reaches this device. */
    val connectionId: String? = null
)

@Serializable
data class AcquireResponse(
    val leaseId: String,
    val acquiredCount: Int,
    val access: AdapterAccess,
    val inventory: List<AdapterDeviceProfile> = emptyList(),
    val capabilities: AdapterCapabilities = AdapterCapabilities(),
    val metadata: Map<String, String> = emptyMap(),
    val devices: List<AdapterLeasedDevice> = emptyList()
)

/** Body of `POST /leases/{leaseId}/renew`: keep the lease for [ttlSeconds] from now. */
@Serializable
data class RenewLeaseRequest(
    val ttlSeconds: Long
)

@Serializable
data class AdapterLease(
    val leaseId: String,
    val deviceIds: List<String> = emptyList(),
    val sessionId: String? = null
)

/** Body of `GET /leases`. */
@Serializable
data class LeasesResponse(
    val leases: List<AdapterLease>
)

/**
 * Body of the manager's `POST /api/v1/providers/register`, sent by adapters that register
 * themselves instead of being listed in msh.yaml. Sent again as a heartbeat.
 */
@Serializable
data class ProviderRegistrationRequest(
    /** Provider name, unique across the manager; letters, digits, '.', '_' and '-'. */
    val name: String,
    /** Base URL the manager uses to reach this adapter, e.g. `http://10.0.0.5:7037`. */
    val url: String,
    /** Host test runners use for adb when it differs from the host in [url]. */
    val accessHost: String? = null,
    /** The adapter's `ADAPTER_SECRET`, so the manager can authenticate its calls back. */
    val secret: String = "",
    val adapterType: String? = null
)

@Serializable
data class ProviderRegistrationResponse(
    val name: String,
    /** Register again at least this often. */
    val heartbeatIntervalSeconds: Long,
    /** Without another heartbeat within this time the provider stops receiving new sessions. */
    val ttlSeconds: Long
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
