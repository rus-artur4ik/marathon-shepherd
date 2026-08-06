package dev.shepherd.api

import dev.shepherd.adapter.api.*
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.provider.AcquireResult
import dev.shepherd.domain.provider.DevicePoolStatus
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.config.ConfigStore
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import java.io.File

internal fun createRouteProviderRegistry(tempDir: File, configName: String, providers: List<DeviceProvider>): ProviderRegistry {
    val configFile = File(tempDir, configName)
    configFile.writeText(
        buildString {
            appendLine("providers:")
            providers.forEach { provider ->
                appendLine("  - name: \"${provider.name}\"")
                appendLine("    url: \"http://127.0.0.1:7037\"")
            }
        }.trimEnd()
    )
    return createRouteProviderRegistry(configFile, providers)
}

/** Variant for tests that need to author the config file themselves (e.g. with secrets). */
internal fun createRouteProviderRegistry(configFile: File, providers: List<DeviceProvider>): ProviderRegistry {
    val configStore = ConfigStore(configFile.absolutePath)
    val httpClient = HttpClient(CIO)
    return ProviderRegistry(configStore, httpClient) { providerConfig, _ ->
        providers.first { provider -> provider.name == providerConfig.name }
    }
}

internal class RouteTestProvider(
    private val availableAfterQueryCount: Int = 1,
    var availableDevices: Int = 1,
    override val name: String = "route-provider",
    override val inventory: List<AdapterDeviceProfile> = listOf(
        AdapterDeviceProfile(
            deviceType = DEVICE_TYPE_EMULATOR,
            apiLevel = "34",
            count = 1
        )
    ),
    private val supportedDeviceTypes: List<String> = listOf(DEVICE_TYPE_EMULATOR),
    private val supportedApiLevels: List<String> = listOf("34", "35"),
    private val healthy: Boolean = true,
) : DeviceProvider {
    private var queryCount: Int = 0

    override val adbServer: AdbServer = AdbServer("127.0.0.1", 7600)
    override val access: AdapterAccess = AdapterAccess(
        preferredConnectionId = "primary",
        connections = listOf(
            AdapterConnection(
                id = "primary",
                protocol = ACCESS_PROTOCOL_ADB,
                transport = ACCESS_TRANSPORT_TCP,
                host = "127.0.0.1",
                port = 7600,
                exposure = ACCESS_EXPOSURE_DIRECT_TCP,
                auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK)
            )
        )
    )
    override val capabilities: AdapterCapabilities = AdapterCapabilities(
        supportedDeviceTypes = supportedDeviceTypes,
        supportedApiLevels = supportedApiLevels,
        supportsSelectiveApiAllocation = true
    )

    override suspend fun queryDevices(): DevicePoolStatus {
        queryCount += 1
        val available = if (queryCount >= availableAfterQueryCount) availableDevices else 0
        val total = inventory.sumOf { profile -> profile.count }
        return DevicePoolStatus(
            available = available,
            busy = (total - available).coerceAtLeast(0),
            total = total
        )
    }

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult {
        val acquiredCount = minOf(count, availableDevices)
        availableDevices -= acquiredCount
        return AcquireResult(
            leaseId = "lease-${name.takeLast(4)}",
            acquiredCount = acquiredCount,
            adbServers = listOf(adbServer)
        )
    }

    override fun canAllocateApiLevel(apiLevel: String): Boolean = apiLevel in capabilities.supportedApiLevels

    override fun supportsDeviceType(deviceType: String): Boolean = deviceType in capabilities.supportedDeviceTypes

    override suspend fun release(leaseId: String) {
        availableDevices += 1
    }

    override suspend fun isHealthy(): Boolean = healthy
}
