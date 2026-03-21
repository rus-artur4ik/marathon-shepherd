package dev.shepherd.domain.model

import kotlinx.serialization.Serializable
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
    val apiLevel: String,
    val adbServers: List<AdbServer>,
    val createdAt: Instant,
    val expiresAt: Instant,
    val releasedAt: Instant?
)

@Serializable
data class AdbServer(
    val host: String,
    val port: Int
)
