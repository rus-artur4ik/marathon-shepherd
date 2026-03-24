package dev.shepherd.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ShepherdConfig(
    val providers: List<ProviderConfig>,
    /**
     * Controls what happens when a new session is requested but no devices are available.
     * Defaults to [NoDeviceMode.FAIL_IMMEDIATELY] (original behaviour).
     */
    val noDeviceStrategy: NoDeviceStrategyConfig = NoDeviceStrategyConfig()
)

/**
 * Configuration for the no-device strategy.
 *
 * @param mode         How to react when zero devices can be allocated.
 * @param waitTimeoutSeconds  Maximum time to wait in [NoDeviceMode.WAIT_WITH_TIMEOUT] mode.
 *                            Ignored in [NoDeviceMode.FAIL_IMMEDIATELY] mode.
 */
@Serializable
data class NoDeviceStrategyConfig(
    val mode: NoDeviceMode = NoDeviceMode.FAIL_IMMEDIATELY,
    val waitTimeoutSeconds: Long = 60
)

/** Strategy applied when a session request finds zero available devices. */
@Serializable
enum class NoDeviceMode {
    /** Return HTTP 503 immediately (default). */
    FAIL_IMMEDIATELY,
    /** Keep polling providers until at least one device becomes free, or timeout expires. */
    WAIT_WITH_TIMEOUT,
}

/**
 * A provider is any host running a Shepherd adapter (shepherd-adb, shepherd-farm, or shepherd-cuttlefish).
 * The Manager doesn't care which type — all expose the same REST contract.
 *
 * Security note: the config file containing secrets should be readable only by
 * the shepherd process (chmod 600 / appropriate vault/k8s secret binding).
 */
@Serializable
data class ProviderConfig(
    val name: String,
    /** Base URL of the adapter process on the remote host, e.g. http://192.168.1.10:8091 */
    val url: String,
    /**
     * Optional host exposed to CI/test runners for direct TCP access.
     * Defaults to the host part of [url].
     * Set this only when the device access endpoint lives on a different host than the adapter control plane.
     */
    val accessHost: String? = null,
    /**
     * Bearer token that must match ADAPTER_SECRET on the adapter side.
     * Blank means the adapter is running without auth (local dev only).
     */
    val secret: String = ""
)
