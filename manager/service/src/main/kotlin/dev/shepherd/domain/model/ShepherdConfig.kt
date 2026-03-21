package dev.shepherd.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ShepherdConfig(
    val providers: List<ProviderConfig>
)

/**
 * A provider is any host running a Shepherd adapter (adapter-adb, adapter-farm, or adapter-cuttlefish).
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
     * Bearer token that must match ADAPTER_SECRET on the adapter side.
     * Blank means the adapter is running without auth (local dev only).
     */
    val secret: String = ""
)
