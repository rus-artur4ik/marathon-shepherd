package dev.shepherd.domain.provider

import dev.shepherd.domain.model.ShepherdConfig
import dev.shepherd.domain.model.ProviderConfig
import dev.shepherd.infra.config.ConfigStore
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory

interface ProviderCatalog {
    fun activeProviders(): List<DeviceProvider>
    fun resolveProvider(name: String): DeviceProvider?
}

class ProviderRegistry(
    private val configStore: ConfigStore,
    private val httpClient: HttpClient,
    private val providerFactory: (ProviderConfig, HttpClient) -> DeviceProvider = ::createRemoteProvider
) : ProviderCatalog {
    private val logger = LoggerFactory.getLogger(ProviderRegistry::class.java)
    private val lock = Any()

    private var activeConfig: ShepherdConfig = ShepherdConfig(providers = emptyList())
    private var activeProviders: List<DeviceProvider> = emptyList()
    private val knownProvidersByName: MutableMap<String, DeviceProvider> = mutableMapOf()

    init {
        reloadConfig()
    }

    override fun activeProviders(): List<DeviceProvider> {
        synchronized(lock) {
            return activeProviders.toList()
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

    private fun applyConfig(config: ShepherdConfig) {
        val rebuiltProviders: List<DeviceProvider> = config.providers.map { providerConfig ->
            providerFactory(providerConfig, httpClient)
        }
        rebuiltProviders.forEach { provider ->
            knownProvidersByName[provider.name] = provider
        }
        activeConfig = config
        activeProviders = rebuiltProviders
        logger.info(
            "Loaded ${rebuiltProviders.size} active provider(s): ${
                rebuiltProviders.joinToString(", ") { provider -> provider.name }
            }"
        )
    }
}

private fun createRemoteProvider(providerConfig: ProviderConfig, httpClient: HttpClient): DeviceProvider {
    val logger = LoggerFactory.getLogger("dev.shepherd.ProviderFactory")
    if (providerConfig.secret.isBlank()) {
        logger.warn("Provider '${providerConfig.name}' has no secret and will be contacted without authentication")
    }
    return RemoteAdapterProvider(
        name = providerConfig.name,
        adapterUrl = providerConfig.url,
        secret = providerConfig.secret,
        httpClient = httpClient
    )
}
