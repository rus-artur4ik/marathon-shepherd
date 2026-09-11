package dev.shepherd.domain.provider

import dev.shepherd.domain.metrics.ManagerMetrics
import dev.shepherd.domain.model.AdapterTimeoutsConfig
import dev.shepherd.domain.model.ProviderConfig
import dev.shepherd.domain.model.ShepherdConfig
import dev.shepherd.infra.config.ConfigStore
import io.ktor.client.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

interface ProviderCatalog {
    fun activeProviders(): List<DeviceProvider>
    fun resolveProvider(name: String): DeviceProvider?
}

/** An adapter that registered itself over the API instead of being listed in msh.yaml. */
data class ProviderRegistration(
    val name: String,
    val url: String,
    val accessHost: String?,
    val secret: String,
    val adapterType: String?,
    /** The API client that owns this name; only it (or an admin) may re-register it. */
    val clientId: String,
    val clientName: String,
    val registeredAt: Instant,
    val lastSeenAt: Instant
) {
    fun sameEndpoint(other: ProviderRegistration): Boolean = url == other.url && accessHost == other.accessHost && secret == other.secret

    fun toProviderConfig(): ProviderConfig = ProviderConfig(name = name, url = url, accessHost = accessHost, secret = secret)
}

/**
 * Every provider the manager knows: the static ones from msh.yaml plus self-registered
 * adapters. A registration without a recent heartbeat drops out of [activeProviders], so it
 * gets no new sessions, but stays resolvable so its existing leases can still be released.
 */
class ProviderRegistry(
    private val configStore: ConfigStore,
    private val httpClient: HttpClient,
    private val metrics: ManagerMetrics = ManagerMetrics.NONE,
    private val clock: Clock = Clock.systemUTC(),
    providerFactory: ((ProviderConfig, HttpClient) -> DeviceProvider)? = null
) : ProviderCatalog {
    private val logger = LoggerFactory.getLogger(ProviderRegistry::class.java)
    private val lock = Any()

    // Adapter timeouts are read per call, so a config reload applies to providers that
    // were built before it.
    private val providerFactory: (ProviderConfig, HttpClient) -> DeviceProvider = providerFactory
        ?: { providerConfig, client ->
            createRemoteProvider(providerConfig, client, metrics) { currentConfig().adapterTimeouts }
        }

    private data class RegisteredEntry(val registration: ProviderRegistration, val provider: DeviceProvider)

    private var activeConfig: ShepherdConfig = ShepherdConfig(providers = emptyList())
    private var activeProviders: List<DeviceProvider> = emptyList()
    private val knownProvidersByName: MutableMap<String, DeviceProvider> = mutableMapOf()
    private val registered: MutableMap<String, RegisteredEntry> = linkedMapOf()

    init {
        reloadConfig()
    }

    override fun activeProviders(): List<DeviceProvider> {
        synchronized(lock) {
            return activeProviders + registered.values
                .filter { entry -> isFreshLocked(entry.registration) }
                .map { entry -> entry.provider }
        }
    }

    override fun resolveProvider(name: String): DeviceProvider? {
        synchronized(lock) {
            return knownProvidersByName[name]
        }
    }

    fun currentConfig(): ShepherdConfig {
        synchronized(lock) {
            return activeConfig
        }
    }

    fun updateConfig(config: ShepherdConfig): ShepherdConfig {
        synchronized(lock) {
            configStore.updateConfig(config)
            applyConfig(config)
            return activeConfig
        }
    }

    fun reloadConfig(): ShepherdConfig {
        synchronized(lock) {
            val reloadedConfig: ShepherdConfig = configStore.reloadConfig()
            applyConfig(reloadedConfig)
            return activeConfig
        }
    }

    fun staticProviderNames(): Set<String> = synchronized(lock) { activeConfig.providers.map { provider -> provider.name }.toSet() }

    fun registrations(): List<ProviderRegistration> = synchronized(lock) { registered.values.map { entry -> entry.registration } }

    fun registration(name: String): ProviderRegistration? = synchronized(lock) { registered[name]?.registration }

    /** True while [registration] has heartbeated within the configured TTL. */
    fun isFresh(registration: ProviderRegistration): Boolean = synchronized(lock) { isFreshLocked(registration) }

    /** Adds or refreshes a registration; the provider is rebuilt only when its endpoint changed. */
    fun upsertRegistration(registration: ProviderRegistration) {
        synchronized(lock) {
            val existing: RegisteredEntry? = registered[registration.name]
            val provider: DeviceProvider = if (existing != null && existing.registration.sameEndpoint(registration)) {
                existing.provider
            } else {
                providerFactory(registration.toProviderConfig(), httpClient)
            }
            registered[registration.name] = RegisteredEntry(registration, provider)
            knownProvidersByName[registration.name] = provider
        }
    }

    fun removeRegistration(name: String): ProviderRegistration? = synchronized(lock) { registered.remove(name)?.registration }

    private fun isFreshLocked(registration: ProviderRegistration): Boolean =
        Duration.between(registration.lastSeenAt, clock.instant()) <= Duration.ofSeconds(activeConfig.registration.ttlSeconds)

    private fun applyConfig(config: ShepherdConfig) {
        val rebuiltProviders: List<DeviceProvider> = config.providers.map { providerConfig ->
            providerFactory(providerConfig, httpClient)
        }
        rebuiltProviders.forEach { provider ->
            knownProvidersByName[provider.name] = provider
        }
        activeConfig = config
        activeProviders = rebuiltProviders
        // A name configured statically wins over a self-registration with the same name.
        config.providers.map { provider -> provider.name }.filter { name -> name in registered }.forEach { name ->
            registered.remove(name)
            logger.warn("Provider '{}' is now configured in msh.yaml; dropping its self-registration", name)
        }
        logger.info(
            "Loaded ${rebuiltProviders.size} active provider(s): ${
                rebuiltProviders.joinToString(", ") { provider -> provider.name }
            }"
        )
    }
}

private fun createRemoteProvider(
    providerConfig: ProviderConfig,
    httpClient: HttpClient,
    metrics: ManagerMetrics,
    timeouts: () -> AdapterTimeoutsConfig
): DeviceProvider {
    val logger = LoggerFactory.getLogger("dev.shepherd.ProviderFactory")
    if (providerConfig.secret.isBlank()) {
        logger.warn("Provider '${providerConfig.name}' has no secret and will be contacted without authentication")
    }
    return RemoteAdapterProvider(
        name = providerConfig.name,
        adapterUrl = providerConfig.url,
        accessHost = providerConfig.accessHost?.trim().orEmpty().ifBlank { Url(providerConfig.url).host },
        secret = providerConfig.secret,
        httpClient = httpClient,
        metrics = metrics,
        timeouts = timeouts
    )
}
