package dev.shepherd.mcp

import dev.shepherd.protocol.DeviceDto
import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.SessionDeviceDto
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.WhoAmIResponse

/** What an agent needs to know about a session to use it, and what to do next. */
internal fun describeSession(session: SessionResponse): String = buildString {
    when (session.status) {
        STATUS_READY -> {
            append("Session ${session.id} is READY with ${session.allocatedDevices} device(s) until ${session.expiresAt}.")
            session.idleTimeoutSeconds?.let { idle -> append(" It is released after ${idle}s without get_session or wait_for_session.") }
            appendLine()
            if (session.adbServers.isNotEmpty()) {
                appendLine("Connect with adb:")
                session.adbServers.forEach { server -> appendLine("  adb -H ${server.host} -P ${server.port} devices") }
            }
            if (session.devices.isNotEmpty()) {
                appendLine("Devices:")
                session.devices.forEach { device -> appendLine("  ${describe(device)}") }
            }
            append("Call release_session with this session id when you are done.")
        }
        STATUS_PENDING -> {
            append("Session ${session.id} is PENDING: ${session.requestedDevices} device(s) requested")
            session.queuePosition?.let { position -> append(", queue position $position") }
            append(". Call wait_for_session to keep waiting; a queued session that nobody waits on for 90 seconds is dropped.")
        }
        else -> append("Session ${session.id} is ${session.status} and holds no devices.")
    }
}

internal fun formatDevices(response: DevicesResponse): String = buildString {
    if (response.devices.isEmpty()) {
        appendLine("No individually listed device matches.")
    } else {
        appendLine("${response.devices.size} device(s):")
        response.devices.forEach { device -> appendLine("- ${describe(device)}") }
    }
    if (response.providers.isNotEmpty()) {
        appendLine("Providers:")
        response.providers.forEach { provider ->
            val types: String = provider.capabilities.supportedDeviceTypes.joinToString("/").ifEmpty { "any type" }
            val apis: String = provider.capabilities.supportedApiLevels.joinToString(",").ifEmpty { "any" }
            appendLine(
                "- ${provider.name}: ${provider.status}, ${provider.pool.available} available, ${provider.pool.busy} busy " +
                    "($types, API $apis)"
            )
        }
    }
}.trimEnd()

internal fun formatMySessions(me: WhoAmIResponse, sessions: List<SessionResponse>): String = buildString {
    append("API key '${me.name}' (${me.role}) holds ${me.usage.devices} device(s)")
    me.quota.maxDevices?.let { limit -> append(" of at most $limit") }
    appendLine(".")
    if (sessions.isEmpty()) {
        append("No active sessions.")
    } else {
        sessions.forEach { session ->
            append("- ${session.id}: ${session.status}, ${session.allocatedDevices}/${session.requestedDevices} device(s)")
            session.name?.let { name -> append(", \"$name\"") }
            appendLine(", expires ${session.expiresAt}")
        }
    }
}.trimEnd()

private fun describe(device: DeviceDto): String = buildString {
    append("${device.id}: ${device.state}, ${device.deviceType}")
    device.apiLevel?.let { api -> append(", API $api") }
    listOfNotNull(device.manufacturer, device.model).takeIf { parts -> parts.isNotEmpty() }?.let { parts ->
        append(", ${parts.joinToString(" ")}")
    }
    if (device.labels.isNotEmpty()) {
        append(", labels ${device.labels.entries.joinToString(", ") { (key, value) -> "$key=$value" }}")
    }
    device.sessionId?.let { id -> append(", held by ${device.owner ?: "another client"} in $id") }
    device.maintenance?.let { maintenance -> append(", in maintenance" + maintenance.reason?.let { reason -> ": $reason" }.orEmpty()) }
}

private fun describe(device: SessionDeviceDto): String = buildString {
    append(device.id)
    device.model?.let { model -> append(" ($model)") }
    device.apiLevel?.let { api -> append(", API $api") }
    device.adbServer?.let { server -> append(", via adb -H ${server.host} -P ${server.port}") }
}
