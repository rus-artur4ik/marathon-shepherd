package dev.shepherd.adapter.adb

sealed interface AdbReloadOutcome {
    data class Success(val output: String) : AdbReloadOutcome
    data class Busy(val activeLeaseIds: List<String>) : AdbReloadOutcome
    data class Failed(val message: String) : AdbReloadOutcome
}

open class AdbAdminService(
    private val adbService: AdbService,
    private val leaseManager: AdbLeaseManager
) {
    open suspend fun reloadAdbDaemon(): AdbReloadOutcome {
        val activeLeaseIds: List<String> = leaseManager.activeLeaseIds()
        if (activeLeaseIds.isNotEmpty()) {
            return AdbReloadOutcome.Busy(activeLeaseIds)
        }

        val result = adbService.reloadServer()
        if (!result.isSuccess) {
            return AdbReloadOutcome.Failed(
                buildString {
                    append("adb reload failed")
                    if (result.output.isNotBlank()) {
                        append(": ")
                        append(result.output.trim())
                    }
                }
            )
        }
        return AdbReloadOutcome.Success(result.output)
    }
}
