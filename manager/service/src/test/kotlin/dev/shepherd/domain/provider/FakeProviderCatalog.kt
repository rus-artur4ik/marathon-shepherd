package dev.shepherd.domain.provider

class FakeProviderCatalog(
    private val active: List<DeviceProvider>,
    additionalProviders: List<DeviceProvider> = emptyList()
) : ProviderCatalog {
    private val providersByName: Map<String, DeviceProvider> = (active + additionalProviders)
        .associateBy { provider -> provider.name }

    override fun activeProviders(): List<DeviceProvider> = active

    override fun resolveProvider(name: String): DeviceProvider? = providersByName[name]
}
