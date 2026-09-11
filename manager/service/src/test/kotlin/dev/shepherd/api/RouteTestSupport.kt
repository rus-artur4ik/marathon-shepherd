package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.adapter.api.*
import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.devices.DeviceCatalog
import dev.shepherd.domain.events.EventBus
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.provider.AcquireResult
import dev.shepherd.domain.provider.DevicePoolStatus
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.ProviderRegistrationService
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.audit.AuditStore
import dev.shepherd.infra.audit.StoreAuditTrail
import dev.shepherd.infra.auth.AccessControl
import dev.shepherd.infra.auth.ClientStore
import dev.shepherd.infra.config.ConfigStore
import dev.shepherd.infra.devices.MaintenanceStore
import dev.shepherd.infra.metrics.MicrometerManagerMetrics
import dev.shepherd.infra.providers.RegistrationStore
import dev.shepherd.infra.state.StateStore
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
    // Self-registered adapters are not in the fixture list; give them an empty fake.
    return ProviderRegistry(configStore, httpClient) { providerConfig, _ ->
        providers.firstOrNull { provider -> provider.name == providerConfig.name }
            ?: dev.shepherd.domain.provider.FakeDeviceProvider(name = providerConfig.name, totalDevices = 0)
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

/** Static admin token every route test authenticates with. */
internal const val TEST_ADMIN_TOKEN: String = "test-admin-token"

/** Wires the HTTP layer the way `main` does, minus the background jobs. */
internal fun managerServices(
    providerRegistry: ProviderRegistry,
    stateStore: StateStore,
    sessionManager: SessionManager? = null,
    metrics: MicrometerManagerMetrics = MicrometerManagerMetrics()
): ManagerServices {
    val auditStore = AuditStore(stateStore.db)
    val audit = StoreAuditTrail(auditStore)
    val eventBus = EventBus()
    val maintenanceStore = MaintenanceStore(stateStore.db)
    val fleetMonitor = FleetMonitor(
        providerCatalog = providerRegistry,
        sessionCounts = { stateStore.countActiveSessions() },
        metrics = metrics,
        events = eventBus
    )
    return ManagerServices(
        providerRegistry = providerRegistry,
        stateStore = stateStore,
        sessionManager = sessionManager ?: SessionManager(
            providerCatalog = providerRegistry,
            stateStore = stateStore,
            audit = audit,
            events = eventBus,
            maintenanceDeviceIds = { maintenanceStore.all().keys },
            queuePolicy = { providerRegistry.currentConfig().scheduler.policy }
        ),
        fleetMonitor = fleetMonitor,
        accessControl = AccessControl(
            clients = ClientStore(stateStore.db),
            audit = audit,
            quotaDefaults = { providerRegistry.currentConfig().quotas.defaults.toQuota() },
            staticAdminToken = TEST_ADMIN_TOKEN
        ),
        auditStore = auditStore,
        audit = audit,
        eventBus = eventBus,
        deviceCatalog = DeviceCatalog(
            fleetMonitor = fleetMonitor,
            readySessions = { stateStore.listSessions(SessionStatus.READY) },
            maintenance = maintenanceStore,
            audit = audit,
            events = eventBus
        ),
        registrations = ProviderRegistrationService(
            registry = providerRegistry,
            repository = RegistrationStore(stateStore.db),
            hasActiveLeases = { name -> stateStore.hasActiveLeasesForProvider(name) },
            audit = audit,
            events = eventBus
        ),
        metrics = metrics
    )
}

/** Creates a client straight through [AccessControl] and returns its API key. */
internal suspend fun ManagerServices.issueKey(name: String, role: Role = Role.USER, quota: ClientQuota = ClientQuota.UNLIMITED): String =
    accessControl.createClient(Actor.SYSTEM, name, role, description = null, quota = quota).apiKey
