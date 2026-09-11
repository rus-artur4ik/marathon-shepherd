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
    val api: String?,
    val deviceType: String?,
    val adbServers: List<AdbServer>,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastHeartbeatAt: Instant,
    val releasedAt: Instant?,
    /** The client that created the session; null for sessions created before ownership existed. */
    val ownerId: String? = null,
    val ownerName: String? = null,
    /** Display name, e.g. a CI job and build number. */
    val name: String? = null,
    /** Client-supplied key/value pairs, returned as-is. */
    val metadata: Map<String, String> = emptyMap(),
    /** Queue priority under the `priority` scheduler policy. */
    val priority: Int = 0,
    /** A READY session without a heartbeat for this long is released. */
    val idleTimeoutSeconds: Long? = null,
    /** Every allocated device must carry these labels. */
    val labels: Map<String, String> = emptyMap(),
    /** Global ids of the only devices this session may receive; empty means any. */
    val deviceIds: List<String> = emptyList(),
    /** Devices held while READY, for providers that report individual devices. */
    val devices: List<SessionDevice> = emptyList()
)

/** One device a session holds. */
@Serializable
data class SessionDevice(
    /** Global id, `<provider>:<device id>`. */
    val id: String,
    val provider: String,
    val localId: String,
    /** The adb server endpoint that reaches this device, when the adapter says which one. */
    val adbServer: AdbServer? = null,
    val model: String? = null,
    val apiLevel: String? = null
)

/** Everything about a session request beyond device count, API level, TTL and device type. */
data class SessionOptions(
    val name: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val priority: Int = 0,
    val idleTimeoutSeconds: Long? = null,
    val labels: Map<String, String> = emptyMap(),
    val deviceIds: List<String> = emptyList()
)

/** What one client currently asks for or holds. */
data class OwnerUsage(
    val activeSessions: Int,
    /** Queued sessions count the devices they asked for, ready ones the devices they got. */
    val devices: Int
)

/** The wire type is the domain type: an adb endpoint has exactly one shape. */
typealias AdbServer = dev.shepherd.protocol.AdbServer

/** Counts of sessions that are queued or holding devices. */
data class ActiveSessionCounts(
    val pending: Int,
    val ready: Int,
    /** Devices held by READY sessions. */
    val allocatedDevices: Int
) {
    companion object {
        val NONE = ActiveSessionCounts(pending = 0, ready = 0, allocatedDevices = 0)
    }
}
