package dev.shepherd.protocol

import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDeviceProfile
import kotlinx.serialization.Serializable

@Serializable
data class PoolStatus(
    val available: Int,
    val busy: Int,
    val total: Int
)

@Serializable
data class DevicesResponse(
    val providers: List<ProviderStatusDto>,
    val totalAvailable: Int,
    val totalBusy: Int,
    /** Individual devices of providers that report them, after the request's filters. */
    val devices: List<DeviceDto> = emptyList()
)

/** One device as the manager sees it. */
@Serializable
data class DeviceDto(
    /** Global id, `<provider>:<device id>`; use it in `deviceIds` and in device URLs. */
    val id: String,
    val provider: String,
    val localId: String,
    val deviceType: String,
    /** available, busy, offline or maintenance. */
    val state: String,
    val apiLevel: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val abi: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val details: Map<String, String> = emptyMap(),
    val sessionId: String? = null,
    val owner: String? = null,
    val maintenance: MaintenanceDto? = null
)

@Serializable
data class MaintenanceDto(
    val reason: String? = null,
    val by: String,
    val since: String
)

/** Body of `PUT /api/v1/devices/{id}/maintenance`. */
@Serializable
data class MaintenanceRequest(
    val reason: String? = null
)

@Serializable
data class ProviderStatusDto(
    val name: String,
    val adbHost: String,
    val adbPort: Int,
    val access: AdapterAccess,
    val capabilities: AdapterCapabilities,
    val inventory: List<AdapterDeviceProfile>,
    val pool: PoolStatus,
    val status: String
)
