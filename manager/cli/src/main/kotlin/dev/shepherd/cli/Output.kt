package dev.shepherd.cli

import dev.shepherd.protocol.AuditPage
import dev.shepherd.protocol.ClientDto
import dev.shepherd.protocol.ClientKeyResponse
import dev.shepherd.protocol.DeviceDto
import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.EventDto
import dev.shepherd.protocol.ProviderInfoDto
import dev.shepherd.protocol.ProviderStatusDto
import dev.shepherd.protocol.QuotaDto
import dev.shepherd.protocol.SessionDeviceDto
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.ShepherdHealthResponse
import dev.shepherd.protocol.WhoAmIResponse
import kotlinx.serialization.json.JsonPrimitive

/** Aligns [rows] under [headers]; the last column is not padded. */
internal fun table(headers: List<String>, rows: List<List<String>>): String {
    val lines: List<List<String>> = listOf(headers) + rows
    val widths: List<Int> = headers.indices.map { column -> lines.maxOf { line -> line[column].length } }
    return lines.joinToString("\n") { line ->
        line.mapIndexed { column, cell -> if (column == line.lastIndex) cell else cell.padEnd(widths[column]) }
            .joinToString("  ")
            .trimEnd()
    }
}

internal fun formatSession(session: SessionResponse): String = buildString {
    appendLine("Session ${session.id} (${session.status})")
    session.name?.let { name -> appendLine("Name: $name") }
    session.owner?.let { owner -> appendLine("Owner: $owner") }
    appendLine("Devices: ${session.allocatedDevices}/${session.requestedDevices}")
    session.queuePosition?.let { position -> appendLine("Queue position: $position") }
    appendLine("Created: ${session.createdAt}")
    appendLine("Expires: ${session.expiresAt}")
    session.idleTimeoutSeconds?.let { idle ->
        appendLine("Idle timeout: ${idle}s" + session.lastHeartbeatAt?.let { at -> ", last heartbeat $at" }.orEmpty())
    }
    if (session.metadata.isNotEmpty()) {
        appendLine("Metadata: ${pairs(session.metadata)}")
    }
    session.devices.forEach { device -> appendLine("Device: ${describe(device)}") }
    append("ADB servers: ${session.adbServers.joinToString(", ") { server -> "${server.host}:${server.port}" }.ifEmpty { "none" }}")
}

internal fun formatSessionTable(sessions: List<SessionResponse>): String {
    if (sessions.isEmpty()) {
        return "No active sessions."
    }
    return table(
        listOf("ID", "STATUS", "DEVICES", "OWNER", "NAME", "EXPIRES"),
        sessions.map { session ->
            listOf(
                session.id,
                session.status + session.queuePosition?.let { position -> " #$position" }.orEmpty(),
                "${session.allocatedDevices}/${session.requestedDevices}",
                session.owner ?: "-",
                session.name ?: "-",
                session.expiresAt
            )
        }
    )
}

internal fun formatDevices(devices: DevicesResponse): String = buildString {
    appendLine("Providers")
    devices.providers.forEach { provider -> appendLine(formatProviderStatus(provider)) }
    if (devices.devices.isNotEmpty()) {
        appendLine()
        appendLine(
            table(
                listOf("DEVICE", "STATE", "TYPE", "API", "MODEL", "SESSION", "LABELS"),
                devices.devices.map { device ->
                    listOf(
                        device.id,
                        device.state,
                        device.deviceType,
                        device.apiLevel ?: "-",
                        listOfNotNull(device.manufacturer, device.model).joinToString(" ").ifEmpty { "-" },
                        device.sessionId?.let { id -> id + device.owner?.let { owner -> " ($owner)" }.orEmpty() } ?: "-",
                        pairs(device.labels).ifEmpty { "-" }
                    )
                }
            )
        )
    }
    append("Totals: available ${devices.totalAvailable}, busy ${devices.totalBusy}")
}

internal fun formatDevice(device: DeviceDto): String = buildString {
    appendLine("Device ${device.id} (${device.state})")
    appendLine("Provider: ${device.provider}, local id ${device.localId}")
    appendLine("Type: ${device.deviceType}")
    device.apiLevel?.let { api -> appendLine("API level: $api") }
    listOfNotNull(device.manufacturer, device.model).takeIf { parts -> parts.isNotEmpty() }?.let { parts ->
        appendLine("Model: ${parts.joinToString(" ")}")
    }
    device.abi?.let { abi -> appendLine("ABI: $abi") }
    if (device.labels.isNotEmpty()) {
        appendLine("Labels: ${pairs(device.labels)}")
    }
    device.sessionId?.let { id -> appendLine("Session: $id" + device.owner?.let { owner -> " ($owner)" }.orEmpty()) }
    device.maintenance?.let { maintenance ->
        appendLine("Maintenance: ${maintenance.reason ?: "no reason given"}, by ${maintenance.by} since ${maintenance.since}")
    }
    if (device.details.isNotEmpty()) {
        appendLine("Details: ${pairs(device.details)}")
    }
}.trimEnd()

internal fun formatProviders(providers: List<ProviderInfoDto>): String {
    if (providers.isEmpty()) {
        return "No providers."
    }
    return table(
        listOf("NAME", "SOURCE", "STATE", "TYPE", "URL", "LAST SEEN"),
        providers.map { provider ->
            val state: String = when {
                !provider.active -> "inactive"
                provider.healthy == true -> "healthy"
                provider.healthy == false -> "unhealthy"
                else -> "unknown"
            }
            listOf(provider.name, provider.source, state, provider.adapterType ?: "-", provider.url, provider.lastSeenAt ?: "-")
        }
    )
}

internal fun formatEvent(event: EventDto): String {
    val fields: List<String> = event.data.entries.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.let { primitive -> "$key=${primitive.content}" }
    }
    return "${event.at}  #${event.id}  ${event.type}  ${fields.joinToString(" ")}".trimEnd()
}

internal fun formatHealth(health: ShepherdHealthResponse): String = buildString {
    append("Health: ${health.status} | version ${health.version} | ")
    append("${health.providersHealthy}/${health.providersTotal} provider(s) healthy")
    health.sessions?.let { sessions ->
        append("\nSessions: ${sessions.ready} ready, ${sessions.pending} queued, ${sessions.allocatedDevices} device(s) allocated")
    }
    health.providers.forEach { provider ->
        append("\n- ${provider.name}: ${provider.status}")
        provider.total?.let { total -> append(" | available ${provider.available ?: 0}, busy ${provider.busy ?: 0}, total $total") }
        provider.error?.let { error -> append(" | $error") }
    }
}

internal fun formatQuota(quota: QuotaDto): String = listOfNotNull(
    quota.maxDevices?.let { devices -> "devices=$devices" },
    quota.maxSessionLifetimeSeconds?.let { seconds -> "lifetime=${seconds}s" },
    quota.maxPriority?.let { priority -> "priority=$priority" }
).joinToString(" ").ifEmpty { "unlimited" }

internal fun formatWhoAmI(me: WhoAmIResponse): String = "${me.name} (${me.role}), ${me.kind} ${me.id}\n" +
    "Quota: ${formatQuota(me.quota)}\n" +
    "Usage: ${me.usage.activeSessions} active session(s), ${me.usage.devices} device(s)"

internal fun formatClient(client: ClientDto): String = buildString {
    appendLine("Client ${client.name} (${client.id})")
    appendLine("Role: ${client.role}")
    appendLine("Key: ${client.keyPrefix}...")
    client.description?.let { description -> appendLine("Description: $description") }
    appendLine("Quota: ${formatQuota(client.effectiveQuota)}")
    appendLine((if (client.active) "Active" else "Revoked") + client.revokedAt?.let { at -> " since $at" }.orEmpty())
    append("Created: ${client.createdAt}" + client.createdBy?.let { by -> " by $by" }.orEmpty())
    client.lastUsedAt?.let { at -> append("\nLast used: $at") }
}

internal fun formatClients(clients: List<ClientDto>): String {
    if (clients.isEmpty()) {
        return "No clients."
    }
    return table(
        listOf("ID", "NAME", "ROLE", "KEY", "STATE", "LAST USED", "QUOTA"),
        clients.map { client ->
            listOf(
                client.id,
                client.name,
                client.role,
                "${client.keyPrefix}...",
                if (client.active) "active" else "revoked",
                client.lastUsedAt ?: "-",
                formatQuota(client.effectiveQuota)
            )
        }
    )
}

internal fun formatIssuedKey(verb: String, issued: ClientKeyResponse): String =
    "$verb ${issued.client.role} client '${issued.client.name}' (${issued.client.id}).\n" +
        "API key, shown only this once:\n${issued.apiKey}"

internal fun formatAudit(page: AuditPage): String {
    if (page.entries.isEmpty()) {
        return "No audit entries."
    }
    val rows: List<List<String>> = page.entries.map { entry ->
        listOf(
            entry.id.toString(),
            entry.at,
            entry.actor,
            entry.action,
            entry.target ?: "-",
            entry.outcome,
            pairs(entry.details).ifEmpty { "-" }
        )
    }
    return table(listOf("ID", "AT", "ACTOR", "ACTION", "TARGET", "OUTCOME", "DETAILS"), rows) +
        page.nextBefore?.let { before -> "\nOlder entries: mshctl audit --before $before" }.orEmpty()
}

private fun formatProviderStatus(provider: ProviderStatusDto): String {
    val connection = provider.access.connections.firstOrNull { candidate -> candidate.id == provider.access.preferredConnectionId }
        ?: provider.access.connections.firstOrNull()
    val access: String = connection?.let { published ->
        "${published.protocol}/${published.transport} ${published.host}:${published.port} " +
            "[${published.exposure}, auth=${published.auth.type}]"
    } ?: "no published access"
    val deviceTypes: List<String> = provider.capabilities.supportedDeviceTypes
        .ifEmpty { provider.inventory.map { profile -> profile.deviceType }.distinct() }
    val inventory: String = provider.inventory.joinToString("; ") { profile ->
        listOfNotNull(
            profile.deviceType,
            profile.apiLevel?.let { api -> "api=$api" },
            profile.manufacturer,
            profile.model,
            profile.abi?.let { abi -> "abi=$abi" },
            "x${profile.count}"
        ).joinToString(" ")
    }.ifEmpty { "inventory unavailable" }
    return "- ${provider.name}: ${provider.status} | " +
        "available ${provider.pool.available}, busy ${provider.pool.busy}, total ${provider.pool.total} | $access\n" +
        "  deviceTypes=${deviceTypes.joinToString(", ").ifEmpty { "unknown" }} | " +
        "supportedApiLevels=${provider.capabilities.supportedApiLevels.joinToString(", ").ifEmpty { "unspecified" }} | " +
        "features=${provider.capabilities.features.joinToString(", ").ifEmpty { "none" }}\n" +
        "  $inventory"
}

private fun describe(device: SessionDeviceDto): String = buildString {
    append(device.id)
    device.model?.let { model -> append(" $model") }
    device.apiLevel?.let { api -> append(" api=$api") }
    device.adbServer?.let { server -> append(" via ${server.host}:${server.port}") }
}

private fun pairs(values: Map<String, String>): String = values.entries.joinToString(", ") { (key, value) -> "$key=$value" }
