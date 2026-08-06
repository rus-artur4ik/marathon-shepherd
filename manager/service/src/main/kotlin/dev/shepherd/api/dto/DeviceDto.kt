package dev.shepherd.api.dto

import dev.shepherd.adapter.api.*
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
    val fallbackConnection = access.connections.firstOrNull { connection ->
        connection.protocol == ACCESS_PROTOCOL_ADB && connection.transport == ACCESS_TRANSPORT_TCP
    }
    val fallbackHost = access.metadata["resolvedAccessHost"] ?: fallbackConnection?.host ?: "unknown"
    val fallbackPort = fallbackConnection?.port ?: 5037
    return ProviderStatusDto(
        name = name,
        adbHost = preferredConnection?.host ?: fallbackHost,
        adbPort = preferredConnection?.port ?: fallbackPort,
        access = access,
        capabilities = capabilities,
        inventory = inventory,
        pool = pool,
        status = if (isHealthy) "HEALTHY" else "UNREACHABLE"
    )
}
