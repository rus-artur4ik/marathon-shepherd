package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.CommandResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdbAdminServiceTest {

    @Test
    fun `reloadAdbDaemon should report busy when leases are active`() = runBlocking {
        val leaseManager = AdbLeaseManager(
            leaseStore = InMemoryAdbLeaseStore(),
            proxyController = NoOpAdbProxyController()
        )
        val devices = listOf(
            AdbPhysicalDevice(serial = "serial-1", apiLevel = "34", manufacturer = "Google", model = "Pixel", abi = "arm64-v8a")
        )
        val acquireResult = leaseManager.acquireDevices(requestedCount = 1, apiLevel = "34", connectedDevices = devices)
        val service = AdbAdminService(
            adbService = AdbService(commandRunner = { _, _ -> CommandResult(0, "", true) }),
            leaseManager = leaseManager
        )

        val outcome = service.reloadAdbDaemon()

        val busy = assertIs<AdbReloadOutcome.Busy>(outcome)
        assertTrue(busy.activeLeaseIds.contains(requireNotNull(acquireResult.leaseId)))
    }

    @Test
    fun `reloadAdbDaemon should return success after successful reload`() = runBlocking {
        val service = AdbAdminService(
            adbService = AdbService(
                commandRunner = { command, _ ->
                    when (command) {
                        listOf("adb", "kill-server") -> CommandResult(0, "", true)
                        listOf("adb", "start-server") -> CommandResult(0, "daemon started successfully", true)
                        else -> CommandResult(1, "unexpected command", false)
                    }
                }
            ),
            leaseManager = AdbLeaseManager(
                leaseStore = InMemoryAdbLeaseStore(),
                proxyController = NoOpAdbProxyController()
            )
        )

        val outcome = service.reloadAdbDaemon()

        val success = assertIs<AdbReloadOutcome.Success>(outcome)
        assertTrue(success.output.contains("daemon started"))
    }

    @Test
    fun `reloadAdbDaemon should surface reload failure`() = runBlocking {
        val service = AdbAdminService(
            adbService = AdbService(
                commandRunner = { command, _ ->
                    when (command) {
                        listOf("adb", "kill-server") -> CommandResult(0, "", true)
                        listOf("adb", "start-server") -> CommandResult(1, "boom", false)
                        else -> CommandResult(1, "unexpected command", false)
                    }
                }
            ),
            leaseManager = AdbLeaseManager(
                leaseStore = InMemoryAdbLeaseStore(),
                proxyController = NoOpAdbProxyController()
            )
        )

        val outcome = service.reloadAdbDaemon()

        val failure = assertIs<AdbReloadOutcome.Failed>(outcome)
        assertEquals("adb reload failed: boom", failure.message)
    }
}

private class NoOpAdbProxyController : AdbProxyController {
    override fun startDeviceProxy(leaseId: String, device: AdbPhysicalDevice, preferredPort: Int?): Int = 7600
    override fun stopLease(leaseId: String) = Unit
}
