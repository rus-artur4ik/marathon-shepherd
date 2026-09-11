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
    val totalBusy: Int
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
