package dev.shepherd.domain.allocation

import dev.shepherd.domain.model.AnyLevel
import dev.shepherd.domain.model.ApiSelector
import dev.shepherd.domain.provider.DeviceProvider

/**
 * Decides which providers can satisfy a device request, and how many devices each one
 * could contribute.
 *
 * This is pure policy over a provider's last-known inventory and declared capabilities —
 * no I/O, no session state. It is split out of `SessionManager` so the matching rules can
 * be read and tested on their own; `SessionManager` stays about session lifecycle.
 */
object ProviderMatcher {

    /** Capability flag marking a provider that can create devices on demand (e.g. Cuttlefish). */
    private const val EPHEMERAL_VM_FEATURE = "ephemeral-vm"

    /**
     * True when at least one provider either already holds a matching device or could
     * create one on demand. Used to distinguish "busy, so queue" from "impossible, so fail".
     */
    fun hasRegisteredMatchingDevices(providers: List<DeviceProvider>, deviceType: String?, apiSelector: ApiSelector): Boolean =
        providers.any { provider ->
            matchingRegisteredDevices(provider, deviceType, apiSelector) > 0 ||
                canAllocateOnDemand(provider, deviceType, apiSelector)
        }

    /** How many devices in [provider]'s current inventory satisfy the request. */
    fun matchingRegisteredDevices(provider: DeviceProvider, deviceType: String?, apiSelector: ApiSelector): Int {
        val candidateApiLevels: Set<String> = resolveCandidateApiLevels(provider, apiSelector).toSet()
        if (candidateApiLevels.isEmpty()) {
            return 0
        }
        val matchingProfiles = provider.inventory
            .filter { profile -> deviceType == null || profile.deviceType == deviceType }
        if (matchingProfiles.isEmpty()) {
            return 0
        }

        val apiAwareProfiles = matchingProfiles.filter { profile -> profile.apiLevel != null }
        if (apiAwareProfiles.isNotEmpty()) {
            return apiAwareProfiles
                .filter { profile -> profile.apiLevel in candidateApiLevels }
                .sumOf { profile -> profile.count }
        }

        // Some adapters publish aggregated pool inventory without per-profile apiLevel.
        // In that case supportedApiLevels is the only available selector signal.
        return matchingProfiles.sumOf { profile -> profile.count }
    }

    /** True when [provider] can spin up a matching device that does not exist yet. */
    fun canAllocateOnDemand(provider: DeviceProvider, deviceType: String?, apiSelector: ApiSelector): Boolean {
        if (!supportsOnDemandAllocation(provider)) {
            return false
        }
        if (deviceType != null && !provider.supportsDeviceType(deviceType)) {
            return false
        }
        return resolveCandidateApiLevels(provider, apiSelector).isNotEmpty()
    }

    fun supportsOnDemandAllocation(provider: DeviceProvider): Boolean = EPHEMERAL_VM_FEATURE in provider.capabilities.features

    /**
     * The API levels this provider could actually serve for [apiSelector].
     *
     * Inventory wins over declared capabilities, because it describes devices that exist
     * right now. A provider that cannot target a specific API level (
     * `supportsSelectiveApiAllocation == false`) is only usable when the selector narrows
     * to exactly one level — otherwise allocation would be a coin flip.
     */
    fun resolveCandidateApiLevels(provider: DeviceProvider, apiSelector: ApiSelector): List<String> {
        val inventoryApiLevels: List<String> = provider.inventory.mapNotNull { profile -> profile.apiLevel }
        val matchedInventoryApiLevels: List<String> = apiSelector.matchingLevels(inventoryApiLevels)
        if (matchedInventoryApiLevels.isNotEmpty()) {
            return narrow(provider, matchedInventoryApiLevels)
        }

        val supportedApiLevels: List<String> = provider.capabilities.supportedApiLevels
        val matchedSupportedApiLevels: List<String> = apiSelector.matchingLevels(supportedApiLevels)
        if (matchedSupportedApiLevels.isNotEmpty()) {
            return narrow(provider, matchedSupportedApiLevels)
        }

        // A rack that declares nothing but holds one uniform API level is unambiguous,
        // so an unconstrained request can still be served from it.
        if (apiSelector === AnyLevel && provider.capabilities.supportedApiLevels.isEmpty()) {
            val homogeneousInventoryApiLevels: Set<String> = inventoryApiLevels.toSet()
            if (homogeneousInventoryApiLevels.size == 1) {
                return homogeneousInventoryApiLevels.toList()
            }
        }
        return emptyList()
    }

    private fun narrow(provider: DeviceProvider, matched: List<String>): List<String> {
        if (provider.capabilities.supportsSelectiveApiAllocation) {
            return matched
        }
        return if (matched.size == 1) matched else emptyList()
    }
}
