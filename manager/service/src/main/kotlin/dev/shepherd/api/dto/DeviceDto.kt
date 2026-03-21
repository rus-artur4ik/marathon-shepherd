package dev.shepherd.api.dto

import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.preferredAdbTcpConnection
import dev.shepherd.domain.ProviderStatus
import dev.shepherd.domain.provider.DevicePoolStatus
import kotlinx.serialization.Serializable

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
    val pool: DevicePoolStatus,
    val status: String
)

fun ProviderStatus.toDto(): ProviderStatusDto {
    val preferredConnection = access.preferredAdbTcpConnection()
    return ProviderStatusDto(
        name = name,
        adbHost = preferredConnection?.host ?: "unknown",
        adbPort = preferredConnection?.port ?: 5037,
        access = access,
        capabilities = capabilities,
        inventory = inventory,
        pool = pool,
        status = if (isHealthy) "HEALTHY" else "UNREACHABLE"
    )
}
