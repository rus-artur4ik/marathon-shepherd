package dev.shepherd.api.dto

import dev.shepherd.adapter.api.ACCESS_PROTOCOL_ADB
import dev.shepherd.adapter.api.ACCESS_TRANSPORT_TCP
import dev.shepherd.adapter.api.preferredAdbTcpConnection
import dev.shepherd.domain.ProviderStatus
import dev.shepherd.domain.devices.DeviceView
import dev.shepherd.protocol.DeviceDto
import dev.shepherd.protocol.MaintenanceDto
import dev.shepherd.protocol.ProviderStatusDto

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

fun DeviceView.toDto(): DeviceDto = DeviceDto(
    id = id,
    provider = provider,
    localId = localId,
    deviceType = deviceType,
    state = state,
    apiLevel = apiLevel,
    manufacturer = manufacturer,
    model = model,
    abi = abi,
    labels = labels,
    details = details,
    sessionId = sessionId,
    owner = owner,
    maintenance = maintenance?.let { info ->
        MaintenanceDto(reason = info.reason, by = info.setBy, since = info.setAt.toString())
    }
)
