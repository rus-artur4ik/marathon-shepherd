package dev.shepherd.api.dto

import dev.shepherd.adapter.api.ACCESS_PROTOCOL_ADB
import dev.shepherd.adapter.api.ACCESS_TRANSPORT_TCP
import dev.shepherd.adapter.api.preferredAdbTcpConnection
import dev.shepherd.domain.ProviderStatus
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
