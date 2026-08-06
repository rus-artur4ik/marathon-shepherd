package dev.shepherd.domain

import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.domain.provider.DevicePoolStatus
import dev.shepherd.domain.provider.ProviderCatalog
import org.slf4j.LoggerFactory

class DeviceAllocator(
    private val providerCatalog: ProviderCatalog
) {
    private val logger = LoggerFactory.getLogger(DeviceAllocator::class.java)

    suspend fun getProviderStatuses(): List<ProviderStatus> {
        return providerCatalog.activeProviders().map { provider ->
            val isHealthy = try {
                provider.isHealthy()
            } catch (e: Exception) {
                logger.warn("Provider '${provider.name}' health check failed: ${e.message}")
                false
            }

            val pool = if (isHealthy) {
                try {
                    provider.queryDevices()
                } catch (e: Exception) {
                    logger.warn("Provider '${provider.name}' query failed: ${e.message}")
                    DevicePoolStatus(available = 0, busy = 0, total = 0)
                }
            } else {
                DevicePoolStatus(available = 0, busy = 0, total = 0)
            }

            ProviderStatus(
                name = provider.name,
                access = provider.access,
                capabilities = provider.capabilities,
                inventory = provider.inventory,
                pool = pool,
                isHealthy = isHealthy
            )
        }
    }
}

data class ProviderStatus(
    val name: String,
    val access: AdapterAccess,
    val capabilities: AdapterCapabilities,
    val inventory: List<AdapterDeviceProfile>,
    val pool: DevicePoolStatus,
    val isHealthy: Boolean
)
