package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.CommandResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbServiceTest {

    @Test
    fun `listPhysicalDevices should return only boot completed physical devices`() = runBlocking {
        val service = AdbService(
            commandRunner = { command, _ ->
                when (command) {
                    listOf("adb", "devices") -> CommandResult(
                        0,
                        """
                        List of devices attached
                        serial-ready	device
                        serial-booting	device
                        emulator-5554	device
                        """.trimIndent(),
                        true
                    )

                    listOf("adb", "-s", "serial-ready", "shell", "getprop") -> CommandResult(
                        0,
                        """
                        [sys.boot_completed]: [1]
                        [ro.build.version.sdk]: [34]
                        [ro.product.manufacturer]: [Google]
                        [ro.product.model]: [Pixel 8]
                        [ro.product.cpu.abi]: [arm64-v8a]
                        """.trimIndent(),
                        true
                    )

                    listOf("adb", "-s", "serial-booting", "shell", "getprop") -> CommandResult(
                        0,
                        """
                        [sys.boot_completed]: [0]
                        [dev.bootcomplete]: [0]
                        [init.svc.bootanim]: [running]
                        [ro.build.version.sdk]: [34]
                        [ro.product.manufacturer]: [Google]
                        [ro.product.model]: [Pixel 8]
                        [ro.product.cpu.abi]: [arm64-v8a]
                        """.trimIndent(),
                        true
                    )

                    else -> CommandResult(1, "unexpected command", false)
                }
            }
        )

        val devices = service.listPhysicalDevices()

        assertEquals(1, devices.size)
        assertEquals("serial-ready", devices.single().serial)
    }

    @Test
    fun `listPhysicalDevices should keep devices when boot signals are absent but getprop succeeds`() = runBlocking {
        val service = AdbService(
            commandRunner = { command, _ ->
                when (command) {
                    listOf("adb", "devices") -> CommandResult(
                        0,
                        """
                        List of devices attached
                        serial-ready	device
                        """.trimIndent(),
                        true
                    )

                    listOf("adb", "-s", "serial-ready", "shell", "getprop") -> CommandResult(
                        0,
                        """
                        [ro.build.version.sdk]: [34]
                        [ro.product.manufacturer]: [Google]
                        [ro.product.model]: [Pixel 8]
                        [ro.product.cpu.abi]: [arm64-v8a]
                        """.trimIndent(),
                        true
                    )

                    else -> CommandResult(1, "unexpected command", false)
                }
            }
        )

        val devices = service.listPhysicalDevices()

        assertEquals(1, devices.size)
        assertEquals("serial-ready", devices.single().serial)
    }

    @Test
    fun `reloadServer should kill and restart local adb daemon`() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val service = AdbService(
            adbCommandTimeoutSeconds = 2,
            upstream = AdbSocketAddress(host = "127.0.0.1", port = 5037),
            commandRunner = { command, _ ->
                commands += command
                when (command) {
                    listOf("adb", "kill-server") -> CommandResult(0, "", true)
                    listOf("adb", "start-server") -> CommandResult(0, "daemon started successfully", true)
                    else -> CommandResult(1, "unexpected command", false)
                }
            }
        )

        val result = service.reloadServer()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                listOf("adb", "kill-server"),
                listOf("adb", "start-server")
            ),
            commands
        )
    }

    @Test
    fun `reloadServer should wait for remote adb daemon instead of starting local daemon`() = runBlocking {
        val commands = mutableListOf<List<String>>()
        var devicesAttempts = 0
        val service = AdbService(
            adbCommandTimeoutSeconds = 3,
            upstream = AdbSocketAddress(host = "adb", port = 5037),
            commandRunner = { command, _ ->
                commands += command
                when (command) {
                    listOf("adb", "kill-server") -> CommandResult(0, "", true)
                    listOf("adb", "devices") -> {
                        devicesAttempts += 1
                        if (devicesAttempts < 2) {
                            CommandResult(1, "cannot connect to daemon", false)
                        } else {
                            CommandResult(0, "List of devices attached\n", true)
                        }
                    }
                    else -> CommandResult(1, "unexpected command", false)
                }
            }
        )

        val result = service.reloadServer()

        assertTrue(result.isSuccess)
        assertEquals(listOf("adb", "kill-server"), commands.first())
        assertFalse(commands.contains(listOf("adb", "start-server")))
        assertTrue(commands.count { it == listOf("adb", "devices") } >= 2)
    }

    @Test
    fun `reloadServer should fail when remote adb daemon does not come back`() = runBlocking {
        val service = AdbService(
            adbCommandTimeoutSeconds = 2,
            upstream = AdbSocketAddress(host = "adb", port = 5037),
            commandRunner = { command, _ ->
                when (command) {
                    listOf("adb", "kill-server") -> CommandResult(0, "", true)
                    listOf("adb", "devices") -> CommandResult(1, "cannot connect to daemon", false)
                    else -> CommandResult(1, "unexpected command", false)
                }
            }
        )

        val result = service.reloadServer()

        assertFalse(result.isSuccess)
        assertTrue(result.output.contains("did not become reachable"))
    }
}
