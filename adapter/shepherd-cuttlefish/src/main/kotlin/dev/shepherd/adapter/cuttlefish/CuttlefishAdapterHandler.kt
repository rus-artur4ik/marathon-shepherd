package dev.shepherd.adapter.cuttlefish

import dev.shepherd.adapter.api.*

class CuttlefishAdapterHandler(
    private val cloudOrchestratorService: CloudOrchestratorService
) : AdapterHandler(adapterType = "cuttlefish") {

    override suspend fun isHealthy(): Boolean = cloudOrchestratorService.isHealthy()

    override suspend fun status(): AdapterStatus {
        val running: Int = cloudOrchestratorService.listRunningInstances()
        val leased: Int = cloudOrchestratorService.activeLeaseCount()
        return AdapterStatus(
            pool = AdapterPool(
                available = running - leased,
                busy = leased,
                total = running
            ),
            inventory = listOf(
                AdapterDeviceProfile(
                    deviceType = DEVICE_TYPE_EMULATOR,
                    count = running.coerceAtLeast(0),
                    metadata = mapOf("backend" to "cloud-orchestrator")
                )
            ),
            metadata = mapOf("backend" to "cloud-orchestrator")
        )
    }

    override suspend fun acquire(request: AcquireRequest): AcquireResult {
        val result: CloudOrchestratorAcquireResult = cloudOrchestratorService.createInstances(
            count = request.count,
            apiLevel = request.apiLevel,
            ttlSeconds = request.ttlSeconds
        )
        return AcquireResult(
            leaseId = result.leaseId,
            acquiredCount = result.acquiredCount,
            inventory = listOf(
                AdapterDeviceProfile(
                    deviceType = DEVICE_TYPE_EMULATOR,
                    apiLevel = request.apiLevel,
                    count = result.acquiredCount,
                    metadata = mapOf("backend" to "cloud-orchestrator", "group" to result.group)
                )
            ),
            metadata = mapOf("backend" to "cloud-orchestrator", "group" to result.group)
        )
    }

    override suspend fun release(leaseId: String): Boolean = cloudOrchestratorService.stopInstances(leaseId)

    override fun capabilities(env: AdapterEnv): AdapterCapabilities {
        return super.capabilities(env).copy(
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            supportedApiLevels = cloudOrchestratorService.supportedApiLevels(),
            supportsSelectiveApiAllocation = true,
            features = listOf("inventory", "lease", "release", "ephemeral-vm"),
            metadata = mapOf(
                "controlPlaneAuth" to if (env.authEnabled) "bearer" else "none",
                "backend" to "cloud-orchestrator"
            )
        )
    }
}
