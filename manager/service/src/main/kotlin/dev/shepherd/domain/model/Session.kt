package dev.shepherd.domain.model

import java.time.Instant

enum class SessionStatus {
    PENDING,
    READY,
    FAILED,
    RELEASED,
    EXPIRED
}

data class Session(
    val id: String,
    val status: SessionStatus,
    val requestedDevices: Int,
    val allocatedDevices: Int,
    val api: String?,
    val deviceType: String?,
    val adbServers: List<AdbServer>,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastHeartbeatAt: Instant,
    val releasedAt: Instant?
)

/** The wire type is the domain type: an adb endpoint has exactly one shape. */
typealias AdbServer = dev.shepherd.protocol.AdbServer
