package dev.shepherd.domain.allocation

import dev.shepherd.domain.model.AnyLevel
import dev.shepherd.domain.model.ApiSelector
import dev.shepherd.domain.provider.DeviceProvider

/**
 * Renders the operator-facing explanation for a request that no provider can ever satisfy.
 *
 * This is the message a CI job sees when its build fails, so it is worth being specific:
 * it lists every provider, what it actually holds, and the concrete reason each one was
 * ruled out. Kept apart from `SessionManager` because it is presentation, not policy —
 * and because a 60-line string builder buried in a lifecycle class hides both.
 */
object NoMatchingDevicesReport {

    fun render(providers: List<DeviceProvider>, deviceType: String?, apiSelector: ApiSelector): String {
        val report = StringBuilder("No registered devices match the request${describeRequest(deviceType, apiSelector)}")

        if (providers.isEmpty()) {
            report.append(". No providers are registered")
            return report.toString()
        }

        report.append(". Available providers (${providers.size}):")
        providers.forEach { provider ->
            report.append("\n  • ").append(describeProvider(provider))
            val reasons = rejectionReasons(provider, deviceType, apiSelector)
            if (reasons.isNotEmpty()) {
                report.append(" — ").append(reasons.joinToString("; "))
            }
        }
        return report.toString()
    }

    private fun describeRequest(deviceType: String?, apiSelector: ApiSelector): String {
        val parts = buildList {
            if (deviceType != null) add("deviceType=$deviceType")
            apiSelector.rawValue?.let { api -> add("api=$api") }
        }
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    private fun describeProvider(provider: DeviceProvider): String {
        val inventory = provider.inventory
        val summary = StringBuilder("${provider.name}: ${inventory.sumOf { it.count }} device(s)")
        if (inventory.isNotEmpty()) {
            val profileSummary = inventory
                .groupBy { it.deviceType }
                .entries
                .joinToString(", ") { (type, profiles) ->
                    val apis = profiles.mapNotNull { it.apiLevel }.distinct()
                    val count = profiles.sumOf { it.count }
                    if (apis.isNotEmpty()) "$type[api=${apis.joinToString(",")}]×$count" else "$type×$count"
                }
            summary.append(" ($profileSummary)")
        }
        return summary.toString()
    }

    private fun rejectionReasons(provider: DeviceProvider, deviceType: String?, apiSelector: ApiSelector): List<String> = buildList {
        val supportedDeviceTypes = provider.capabilities.supportedDeviceTypes
        if (deviceType != null && supportedDeviceTypes.isNotEmpty() && deviceType !in supportedDeviceTypes) {
            add("deviceType mismatch: provider supports [${supportedDeviceTypes.joinToString(", ")}]")
        }

        if (apiSelector !== AnyLevel && ProviderMatcher.resolveCandidateApiLevels(provider, apiSelector).isEmpty()) {
            val available = (
                provider.inventory.mapNotNull { it.apiLevel } + provider.capabilities.supportedApiLevels
                ).distinct()
            if (available.isNotEmpty()) {
                add("api mismatch: available [${available.joinToString(", ")}]")
            } else {
                add("api mismatch: no api level info")
            }
        }

        val typeFilteredCount = provider.inventory
            .filter { deviceType == null || it.deviceType == deviceType }
            .sumOf { it.count }
        if (typeFilteredCount == 0 && !ProviderMatcher.supportsOnDemandAllocation(provider)) {
            add("0 devices available")
        }
    }
}
