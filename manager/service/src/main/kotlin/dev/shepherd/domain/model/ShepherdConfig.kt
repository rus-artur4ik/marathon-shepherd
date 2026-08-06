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

/**
 * Placeholder substituted for a non-blank [ProviderConfig.secret] whenever a config
 * leaves the process over HTTP. Chosen so it is obviously not a credential and so a
 * client that round-trips `GET` → edit → `PUT` sends it back unchanged.
 */
const val REDACTED_SECRET: String = "<redacted>"

/**
 * A copy safe to serialize to an API client: every non-blank adapter bearer token is
 * replaced with [REDACTED_SECRET]. Blank secrets stay blank so operators can still see
 * which providers are running unauthenticated.
 *
 * The manager API is unauthenticated by design (see SECURITY.md), so the config
 * endpoints must never emit credentials that would let a caller impersonate the
 * manager against every adapter.
 */
fun ShepherdConfig.redactSecrets(): ShepherdConfig = copy(
    providers = providers.map { provider ->
        if (provider.secret.isBlank()) provider else provider.copy(secret = REDACTED_SECRET)
    }
)

/**
 * Inverse of [redactSecrets] for an incoming `PUT`: any provider whose secret is still
 * the [REDACTED_SECRET] placeholder keeps the secret already held for that provider
 * name in [current], so a read-modify-write cycle cannot silently erase credentials.
 *
 * A provider that is new, or whose secret was genuinely changed by the caller, is left
 * exactly as submitted.
 */
fun ShepherdConfig.restoreRedactedSecrets(current: ShepherdConfig): ShepherdConfig {
    val knownSecrets = current.providers.associate { it.name to it.secret }
    return copy(
        providers = providers.map { provider ->
            if (provider.secret == REDACTED_SECRET) {
                provider.copy(secret = knownSecrets[provider.name].orEmpty())
            } else {
                provider
            }
        }
    )
}
