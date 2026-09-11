package dev.shepherd.domain.allocation

import dev.shepherd.domain.model.ApiSelector

/** What a session asks for, independent of any provider. */
data class DeviceRequest(
    val deviceType: String?,
    val apiSelector: ApiSelector,
    /** Every device must carry all of these labels. */
    val labels: Map<String, String> = emptyMap(),
    /** Global ids of the only acceptable devices; empty means any. */
    val deviceIds: Set<String> = emptySet()
) {
    /** Needs providers that list individual devices and let the manager pick them. */
    val isTargeted: Boolean get() = labels.isNotEmpty() || deviceIds.isNotEmpty()
}
