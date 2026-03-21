package dev.shepherd.adapter.cuttlefish

import dev.shepherd.adapter.api.AcquireRequest
import dev.shepherd.adapter.api.AcquireResult
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.AdapterPool
import dev.shepherd.adapter.api.AdapterStatus
import dev.shepherd.adapter.api.AdapterHandler
import dev.shepherd.adapter.api.DEVICE_TYPE_EMULATOR

class CuttlefishAdapterHandler(
    private val cvdrService: CvdrService
) : AdapterHandler(adapterType = "cuttlefish") {

    override suspend fun isHealthy(): Boolean = cvdrService.isHealthy()

    override suspend fun status(): AdapterStatus {
        val running: Int = cvdrService.listRunningInstances()
        val leased: Int = cvdrService.activeLeaseCount()
        return AdapterStatus(
            pool = AdapterPool(
                available = running - leased,
                busy = leased,
                total = running
            ),
            inventory = listOf(
                AdapterDeviceProfile(
                    deviceType = DEVICE_TYPE_EMULATOR,
                    count = (running - leased).coerceAtLeast(0),
                    metadata = mapOf("backend" to "cvdr")
                )
            ),
            metadata = mapOf("backend" to "cvdr")
        )
    }

    override suspend fun acquire(request: AcquireRequest): AcquireResult {
        val result: CvdrAcquireResult = cvdrService.createInstances(
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
                    metadata = mapOf("backend" to "cvdr", "group" to result.group)
                )
            ),
            metadata = mapOf("backend" to "cvdr", "group" to result.group)
        )
    }

    override suspend fun release(leaseId: String): Boolean = cvdrService.stopInstances(leaseId)

    override fun capabilities(env: AdapterEnv): AdapterCapabilities {
        return super.capabilities(env).copy(
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            supportedApiLevels = cvdrService.supportedApiLevels(),
            supportsSelectiveApiAllocation = true,
            features = listOf("inventory", "lease", "release", "ephemeral-vm"),
            metadata = mapOf(
                "controlPlaneAuth" to if (env.authEnabled) "bearer" else "none",
                "backend" to "cvdr"
            )
        )
    }
}
