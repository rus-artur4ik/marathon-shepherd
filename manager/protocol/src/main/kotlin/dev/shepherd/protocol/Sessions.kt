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
    val expiresAt: String,
    /** Name of the client that created the session. */
    val owner: String? = null
)
