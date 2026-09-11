package dev.shepherd.adapter.cuttlefish

import dev.shepherd.adapter.api.*

class CuttlefishAdapterHandler(
    private val cloudOrchestratorService: CloudOrchestratorService
) : AdapterHandler(adapterType = "cuttlefish") {

    override suspend fun isHealthy(): Boolean = cloudOrchestratorService.isHealthy()

    override suspend fun status(): AdapterStatus {
        // One listing feeds both the counts and the device list, so they describe the same moment
        // and a status poll costs the orchestrator a single round of requests.
        val instances: List<CuttlefishInstance> = cloudOrchestratorService.runningInstances()
        val leases: Map<String, CloudOrchestratorLease> = cloudOrchestratorService.leaseSnapshot()
        val running: Int = instances.size
        val leased: Int = leases.values.sumOf { lease -> lease.count }
        val leaseIdByGroup: Map<String, String> = leases.entries.associate { (leaseId, lease) -> lease.group to leaseId }
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
            metadata = mapOf("backend" to "cloud-orchestrator"),
            devices = instances.mapIndexed { index, instance -> instance.toAdapterDevice(index, leaseIdByGroup) }
        )
    }

    override suspend fun acquire(request: AcquireRequest): AcquireResult {
        val result: CloudOrchestratorAcquireResult = cloudOrchestratorService.createInstances(
            count = request.count,
            apiLevel = request.apiLevel,
            ttlSeconds = request.ttlSeconds,
            sessionId = request.sessionId
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

    /** The orchestrator never expires a group on its own, so a known lease needs nothing to stay alive. */
    override suspend fun renew(leaseId: String, ttlSeconds: Long): Boolean = cloudOrchestratorService.hasLease(leaseId)

    /**
     * The leases this adapter holds. Device ids are left empty: mapping a lease to its instances
     * would take a full orchestrator listing, and the group in the lease already identifies them.
     */
    override suspend fun leases(): List<AdapterLease> = cloudOrchestratorService.leaseSnapshot()
        .entries
        .sortedBy { entry -> entry.key }
        .map { (leaseId, lease) -> AdapterLease(leaseId = leaseId, sessionId = lease.sessionId) }

    override fun capabilities(env: AdapterEnv): AdapterCapabilities {
        return super.capabilities(env).copy(
            supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
            supportedApiLevels = cloudOrchestratorService.supportedApiLevels(),
            supportsSelectiveApiAllocation = true,
            // No device selection: instances are created per acquire, so there is nothing to choose between.
            features = listOf("inventory", "lease", "release", "ephemeral-vm", FEATURE_LEASE_RENEW, FEATURE_LEASE_LIST),
            metadata = mapOf(
                "controlPlaneAuth" to if (env.authEnabled) "bearer" else "none",
                "backend" to "cloud-orchestrator"
            )
        )
    }
}

/**
 * `<group>.<name>`, or the bare name when the group is unknown. The orchestrator numbers instances
 * per group, so names repeat across groups and the group keeps ids unique; anything outside
 * `[A-Za-z0-9._-]` becomes `-` so ids stay safe in URLs and log lines.
 */
internal fun cuttlefishDeviceId(group: String?, name: String): String {
    val rawId: String = if (group.isNullOrBlank()) name else "$group.$name"
    return rawId.replace(UNSAFE_DEVICE_ID_CHARACTERS, "-")
}

private fun CuttlefishInstance.toAdapterDevice(index: Int, leaseIdByGroup: Map<String, String>): AdapterDevice {
    val leaseId: String? = group?.let { groupName -> leaseIdByGroup[groupName] }
    return AdapterDevice(
        // An instance the orchestrator listed without a name still needs some id.
        id = cuttlefishDeviceId(group, name).ifBlank { "instance-${index + 1}" },
        deviceType = DEVICE_TYPE_EMULATOR,
        state = if (leaseId == null) DEVICE_STATE_AVAILABLE else DEVICE_STATE_BUSY,
        leaseId = leaseId,
        metadata = if (group == null) emptyMap() else mapOf("group" to group)
    )
}

private val UNSAFE_DEVICE_ID_CHARACTERS: Regex = Regex("[^A-Za-z0-9._-]")
