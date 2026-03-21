package dev.shepherd.api.dto

import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.Session
import kotlinx.serialization.Serializable

@Serializable
data class CreateSessionRequest(
    val devices: Int = 1,
    val apiLevel: String = "34",
    val ttlSeconds: Long = 3600,
    /**
     * Filter providers by device type. Matches [dev.shepherd.adapter.api.AdapterCapabilities.supportedDeviceTypes].
     * Accepted values: "physical", "emulator". Null = no filter.
     */
    val deviceType: String? = null
)

@Serializable
data class SessionResponse(
    val id: String,
    val status: String,
    val requestedDevices: Int,
    val allocatedDevices: Int,
    val adbServers: List<AdbServer>,
    val createdAt: String,
    val expiresAt: String
)

fun Session.toResponse() = SessionResponse(
    id = id,
    status = status.name,
    requestedDevices = requestedDevices,
    allocatedDevices = allocatedDevices,
    adbServers = adbServers,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt.toString()
)
