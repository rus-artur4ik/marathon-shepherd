package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.*
import java.util.*

class AdbAdapterHandler(
    private val adbService: AdbService
) : AdapterHandler(adapterType = "adb") {

    override suspend fun isHealthy(): Boolean = adbService.isAdbReachable()

    override suspend fun status(): AdapterStatus {
        val inventory: List<AdapterDeviceProfile> = adbService.listPhysicalDeviceProfiles()
        val deviceCount: Int = inventory.sumOf { profile -> profile.count }
        return AdapterStatus(
            pool = AdapterPool(
                available = deviceCount,
                busy = 0,
                total = deviceCount
            ),
            inventory = inventory
        )
    }

    override suspend fun acquire(request: AcquireRequest): AcquireResult {
        val inventory: List<AdapterDeviceProfile> = adbService.listPhysicalDeviceProfiles()
        val totalAvailable: Int = inventory.sumOf { profile -> profile.count }
        if (totalAvailable == 0) {
            return AcquireResult(leaseId = null, acquiredCount = 0)
        }
        if (!canSafelyAllocateRequestedApiLevel(inventory, request.apiLevel)) {
            return AcquireResult(leaseId = null, acquiredCount = 0)
        }
        val matchingCount: Int = inventory.sumOf { profile ->
            if (profile.apiLevel == request.apiLevel) profile.count else 0
        }
        if (matchingCount == 0) {
            return AcquireResult(leaseId = null, acquiredCount = 0)
        }
        val acquiredCount: Int = minOf(request.count, matchingCount)
        val matchingProfile: AdapterDeviceProfile? = inventory.firstOrNull { profile -> profile.apiLevel == request.apiLevel }
        return AcquireResult(
            leaseId = "adb_${UUID.randomUUID().toString().take(8)}",
            acquiredCount = acquiredCount,
            inventory = listOf(
                (matchingProfile?.copy(count = acquiredCount)
                    ?: AdapterDeviceProfile(
                        deviceType = DEVICE_TYPE_PHYSICAL,
                        apiLevel = request.apiLevel,
                        count = acquiredCount
                    ))
            )
        )
    }

    // Physical devices don't need release — they're persistently connected
    override suspend fun release(leaseId: String): Boolean = true

    override fun capabilities(env: AdapterEnv): AdapterCapabilities {
        return super.capabilities(env).copy(
            supportedDeviceTypes = listOf(DEVICE_TYPE_PHYSICAL),
            supportsSelectiveApiAllocation = false,
            metadata = mapOf(
                "controlPlaneAuth" to if (env.authEnabled) "bearer" else "none",
                "apiSelectionMode" to "homogeneous-rack-only"
            )
        )
    }

    private fun canSafelyAllocateRequestedApiLevel(
        inventory: List<AdapterDeviceProfile>,
        requestedApiLevel: String
    ): Boolean {
        val apiLevels: Set<String> = inventory.mapNotNull { profile -> profile.apiLevel }.toSet()
        return apiLevels.size == 1 && apiLevels.single() == requestedApiLevel
    }
}
