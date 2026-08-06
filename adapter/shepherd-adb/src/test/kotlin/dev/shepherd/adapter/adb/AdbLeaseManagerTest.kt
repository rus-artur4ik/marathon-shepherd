package dev.shepherd.adapter.adb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdbLeaseManagerTest {

    @Test
    fun `should allocate only free matching devices and persist busy metadata`() = runBlocking {
        val proxyController = RecordingAdbProxyController()
        val leaseManager = AdbLeaseManager(
            leaseStore = InMemoryAdbLeaseStore(),
            proxyController = proxyController
        )
        val devices = listOf(
            AdbPhysicalDevice(serial = "serial-1", apiLevel = "34", manufacturer = "Google", model = "Pixel", abi = "arm64-v8a"),
            AdbPhysicalDevice(serial = "serial-2", apiLevel = "34", manufacturer = "Google", model = "Pixel", abi = "arm64-v8a"),
            AdbPhysicalDevice(serial = "serial-3", apiLevel = "35", manufacturer = "Google", model = "Pixel", abi = "arm64-v8a")
        )

        val firstAcquire = leaseManager.acquireDevices(requestedCount = 1, apiLevel = "34", connectedDevices = devices)
        val secondAcquire = leaseManager.acquireDevices(requestedCount = 2, apiLevel = "34", connectedDevices = devices)
        val snapshot = leaseManager.buildSnapshot(devices)

        assertEquals(1, firstAcquire.acquiredDevices.size)
        assertNotNull(firstAcquire.leaseId)
        assertEquals(1, secondAcquire.acquiredDevices.size)
        assertTrue(secondAcquire.busySerials.isNotEmpty())
        assertEquals(1, snapshot.availableDevices.size)
        assertEquals(2, snapshot.busyDevices.size)
        assertEquals(2, proxyController.startedDevices.size)
    }

    @Test
    fun `should release lease scoped proxies`() = runBlocking {
        val proxyController = RecordingAdbProxyController()
        val leaseManager = AdbLeaseManager(
            leaseStore = InMemoryAdbLeaseStore(),
            proxyController = proxyController
        )
        val devices = listOf(
            AdbPhysicalDevice(serial = "serial-1", apiLevel = "34", manufacturer = "Google", model = "Pixel", abi = "arm64-v8a")
        )

        val acquire = leaseManager.acquireDevices(requestedCount = 1, apiLevel = "34", connectedDevices = devices)
        val releaseResult = leaseManager.releaseLease(requireNotNull(acquire.leaseId))

        assertTrue(releaseResult)
        assertEquals(listOf(requireNotNull(acquire.leaseId)), proxyController.stoppedLeases)
    }
}

private class RecordingAdbProxyController : AdbProxyController {
    val startedDevices: MutableList<Pair<String, String>> = mutableListOf()
    val stoppedLeases: MutableList<String> = mutableListOf()
    private var nextPort: Int = 7600

    override fun startDeviceProxy(leaseId: String, device: AdbPhysicalDevice, preferredPort: Int?): Int {
        startedDevices += leaseId to device.serial
        return preferredPort ?: nextPort++
    }

    override fun stopLease(leaseId: String) {
        stoppedLeases += leaseId
    }
}
