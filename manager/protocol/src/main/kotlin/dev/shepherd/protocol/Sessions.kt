package dev.shepherd.protocol

import kotlinx.serialization.Serializable

/** An adb server endpoint a test runner talks to, e.g. `adb -H host -P port devices`. */
@Serializable
data class AdbServer(
    val host: String,
    val port: Int
)

@Serializable
data class CreateSessionRequest(
    val maxDevices: Int? = null,
    /** Legacy alias of [maxDevices]. */
    val devices: Int? = null,
    val api: String? = null,
    /** Legacy alias of [api]. */
    val apiLevel: String? = null,
    val ttlSeconds: Long = 3600,
    /**
     * Filter providers by device type. Matches [dev.shepherd.adapter.api.AdapterCapabilities.supportedDeviceTypes].
     * Accepted values: "physical", "emulator". Null = no filter.
     */
    val deviceType: String? = null,
    /** Global ids (`provider:device`) of the only devices this session may receive. */
    val deviceIds: List<String> = emptyList(),
    /** Every device must carry all of these labels. */
    val labels: Map<String, String> = emptyMap(),
    /** Display name, e.g. a CI job and build number. */
    val name: String? = null,
    /** Free-form key/value pairs, returned with the session. */
    val metadata: Map<String, String> = emptyMap(),
    /** Queue priority under the `priority` scheduler policy; limited by the client's quota. */
    val priority: Int = 0,
    /** Release the session when it stays READY without a heartbeat for this long. */
    val idleTimeoutSeconds: Long? = null
) {
    /** Without an explicit count, a request that names devices asks for all of them. */
    fun resolvedMaxDevices(): Int {
        return maxDevices ?: devices ?: deviceIds.size.takeIf { count -> count > 0 } ?: 1
    }

    fun resolvedApi(): String? {
        return api ?: apiLevel
    }
}

@Serializable
data class WaitSessionRequest(
    val timeoutSeconds: Long = 20
)

/** Body of `POST /api/v1/sessions/{id}/extend`: keep the session for [ttlSeconds] from now. */
@Serializable
data class ExtendSessionRequest(
    val ttlSeconds: Long
)

/** A device a session holds. */
@Serializable
data class SessionDeviceDto(
    /** Global id, `<provider>:<device id>`. */
    val id: String,
    val provider: String,
    val localId: String,
    /** The adb server endpoint that reaches this device, when the adapter says which one. */
    val adbServer: AdbServer? = null,
    val model: String? = null,
    val apiLevel: String? = null
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
    val expiresAt: String,
    /** Name of the client that created the session. */
    val owner: String? = null,
    val name: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val priority: Int = 0,
    val idleTimeoutSeconds: Long? = null,
    val labels: Map<String, String> = emptyMap(),
    val deviceIds: List<String> = emptyList(),
    /** Devices held while READY, for providers that report individual devices. */
    val devices: List<SessionDeviceDto> = emptyList(),
    val lastHeartbeatAt: String? = null
)
