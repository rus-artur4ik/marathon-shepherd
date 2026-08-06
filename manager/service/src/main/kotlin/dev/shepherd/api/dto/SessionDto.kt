package dev.shepherd.api.dto

import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.Session
import kotlinx.serialization.Serializable

@Serializable
data class CreateSessionRequest(
    val maxDevices: Int? = null,
    val devices: Int? = null,
    val api: String? = null,
    val apiLevel: String? = null,
    val ttlSeconds: Long = 3600,
    /**
     * Filter providers by device type. Matches [dev.shepherd.adapter.api.AdapterCapabilities.supportedDeviceTypes].
     * Accepted values: "physical", "emulator". Null = no filter.
     */
    val deviceType: String? = null
) {
    fun resolvedMaxDevices(): Int {
        return maxDevices ?: devices ?: 1
    }

    fun resolvedApi(): String? {
        return api ?: apiLevel
    }
}

@Serializable
data class WaitSessionRequest(
    val timeoutSeconds: Long = 20
)

@Serializable
data class SessionResponse(
    val id: String,
    val status: String,
    val requestedDevices: Int,
    val allocatedDevices: Int,
    val api: String? = null,
    val deviceType: String? = null,
    val adbServers: List<AdbServer>,
    val queuePosition: Int? = null,
    val createdAt: String,
    val expiresAt: String
)

fun Session.toResponse(queuePosition: Int? = null) = SessionResponse(
    id = id,
    status = status.name,
    requestedDevices = requestedDevices,
    allocatedDevices = allocatedDevices,
    api = api,
    deviceType = deviceType,
    adbServers = adbServers,
    queuePosition = queuePosition,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt.toString()
)
