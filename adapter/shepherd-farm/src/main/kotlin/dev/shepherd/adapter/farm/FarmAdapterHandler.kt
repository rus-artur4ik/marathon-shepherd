package dev.shepherd.adapter.farm

import dev.shepherd.adapter.api.*

class FarmAdapterHandler(
    private val farmClient: FarmServerClient
) : AdapterHandler(adapterType = "farm") {

    override suspend fun isHealthy(): Boolean = farmClient.isHealthy()

    override suspend fun status(): AdapterStatus {
        val status: FarmStatus = farmClient.getStatus()
        return AdapterStatus(
            pool = AdapterPool(
                available = status.available,
                busy = status.busy,
                total = status.total
            ),
            inventory = listOf(
                AdapterDeviceProfile(
                    deviceType = DEVICE_TYPE_EMULATOR,
                    count = status.total,
                    metadata = mapOf("backend" to "farm-server")
                )
            ),
            metadata = mapOf("backend" to "farm-server")
        )
    }

    override suspend fun acquire(request: AcquireRequest): AcquireResult {
        val result: FarmAcquireResult = farmClient.acquireEmulators(request.count, request.apiLevel, request.ttlSeconds)
        return AcquireResult(
            leaseId = result.leaseId,
            acquiredCount = result.acquiredCount,
            inventory = listOf(
                AdapterDeviceProfile(
                    deviceType = DEVICE_TYPE_EMULATOR,
                    apiLevel = request.apiLevel,
                    count = result.acquiredCount,
                    metadata = mapOf("backend" to "farm-server")
                )
            ),
            metadata = mapOf("backend" to "farm-server")
        )
    }

    override suspend fun release(leaseId: String): Boolean = farmClient.releaseEmulators(leaseId)

    override fun capabilities(env: AdapterEnv): AdapterCapabilities {
        return super.capabilities(env).copy(
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            supportedApiLevels = parseSupportedApiLevels(),
            supportsSelectiveApiAllocation = true,
            features = listOf("inventory", "lease", "release", "ephemeral-emulators"),
            metadata = mapOf(
                "controlPlaneAuth" to if (env.authEnabled) "bearer" else "none",
                "backend" to "farm-server"
            )
        )
    }

    private fun parseSupportedApiLevels(): List<String> {
        return System.getenv("FARM_SUPPORTED_API_LEVELS")
            ?.split(",")
            ?.map { value -> value.trim() }
            ?.filter { value -> value.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }
}
