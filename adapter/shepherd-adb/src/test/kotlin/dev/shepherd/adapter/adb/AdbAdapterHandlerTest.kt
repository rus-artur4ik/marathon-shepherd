package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbAdapterHandlerTest {

    @TempDir
    lateinit var tempDir: File

    private val adb = FakeAdb()
    private val leaseManager = AdbLeaseManager(leaseStore = InMemoryAdbLeaseStore(), proxyController = SequentialPortProxyController())

    @Test
    fun `acquire leases only the requested device ids`() = runBlocking {
        adb.attach("serial-1", "serial-2", "serial-3")

        val result = handler().acquire(request(count = 3, deviceIds = listOf("serial-3", "serial-1")))

        assertEquals(listOf("serial-1", "serial-3"), result.devices.map { device -> device.id })
    }

    @Test
    fun `acquire never leases excluded devices`() = runBlocking {
        adb.attach("serial-1", "serial-2", "serial-3")

        val result = handler().acquire(request(count = 3, excludeDeviceIds = listOf("serial-2")))

        assertEquals(listOf("serial-1", "serial-3"), result.devices.map { device -> device.id })
    }

    @Test
    fun `acquire only leases devices carrying every requested label`() = runBlocking {
        adb.attach("serial-1", "serial-2", "serial-3")
        val handler = handler(
            labelsJson = """{"*": {"rack": "a"}, "serial-2": {"form": "tablet"}, "serial-3": {"form": "tablet", "rack": "b"}}"""
        )

        val result = handler.acquire(request(count = 3, labels = mapOf("form" to "tablet", "rack" to "a")))

        assertEquals(listOf("serial-2"), result.devices.map { device -> device.id })
    }

    @Test
    fun `acquire finds nothing when the selection rules out every device`() = runBlocking {
        adb.attach("serial-1")

        val result = handler().acquire(request(count = 1, deviceIds = listOf("serial-9")))

        assertEquals(0, result.acquiredCount)
        assertNull(result.leaseId)
    }

    @Test
    fun `acquire reports each leased device with the connection that reaches it`() = runBlocking {
        adb.attach("serial-1", "serial-2")

        val result = handler().acquire(request(count = 2))

        assertEquals(
            listOf(
                AdapterLeasedDevice(id = "serial-1", connectionId = "adb-serial-1"),
                AdapterLeasedDevice(id = "serial-2", connectionId = "adb-serial-2")
            ),
            result.devices
        )
        val connections: List<AdapterConnection> = requireNotNull(result.access).connections
        result.devices.forEach { device ->
            assertEquals(device.id, connections.single { connection -> connection.id == device.connectionId }.metadata["serial"])
        }
    }

    @Test
    fun `status reports ready, busy, offline and disconnected devices`() = runBlocking {
        adb.attach("serial-1", "serial-2", "serial-gone")
        val handler = handler(labelsJson = """{"*": {"rack": "a"}}""")
        val busyLease = requireNotNull(handler.acquire(request(count = 1, deviceIds = listOf("serial-2"))).leaseId)
        val goneLease = requireNotNull(handler.acquire(request(count = 1, deviceIds = listOf("serial-gone"))).leaseId)
        adb.detach("serial-gone")
        adb.attachUnavailable("serial-locked", state = "unauthorized")

        val status = handler.status()

        assertEquals(AdapterPool(available = 1, busy = 1, total = 2), status.pool)
        assertEquals(
            listOf(
                pixel(id = "serial-1", state = DEVICE_STATE_AVAILABLE),
                pixel(id = "serial-2", state = DEVICE_STATE_BUSY, leaseId = busyLease),
                pixel(id = "serial-gone", state = DEVICE_STATE_OFFLINE, leaseId = goneLease, reason = "disconnected"),
                AdapterDevice(
                    id = "serial-locked",
                    deviceType = DEVICE_TYPE_PHYSICAL,
                    state = DEVICE_STATE_OFFLINE,
                    labels = mapOf("rack" to "a"),
                    metadata = mapOf("reason" to "unauthorized")
                )
            ),
            status.devices
        )
    }

    @Test
    fun `renew confirms only leases the adapter holds`() = runBlocking {
        adb.attach("serial-1")
        val handler = handler()
        val leaseId = requireNotNull(handler.acquire(request(count = 1)).leaseId)

        assertEquals(true, handler.renew(leaseId, ttlSeconds = 60))
        assertEquals(false, handler.renew("adb_unknown", ttlSeconds = 60))

        handler.release(leaseId)

        assertEquals(false, handler.renew(leaseId, ttlSeconds = 60))
    }

    @Test
    fun `leases lists every active lease with its devices and session`() = runBlocking {
        adb.attach("serial-1", "serial-2", "serial-3")
        val handler = handler()
        val first = requireNotNull(handler.acquire(request(count = 2, sessionId = "session-1")).leaseId)
        val second = requireNotNull(handler.acquire(request(count = 1)).leaseId)

        assertEquals(
            listOf(
                AdapterLease(leaseId = first, deviceIds = listOf("serial-1", "serial-2"), sessionId = "session-1"),
                AdapterLease(leaseId = second, deviceIds = listOf("serial-3"), sessionId = null)
            ).sortedBy { lease -> lease.leaseId },
            handler.leases()
        )
    }

    @Test
    fun `capabilities declare device selection, lease renew and lease list`() {
        val features: List<String> = handler().capabilities(testEnv()).features

        assertTrue(features.containsAll(listOf(FEATURE_DEVICE_SELECTION, FEATURE_LEASE_RENEW, FEATURE_LEASE_LIST)), features.toString())
    }

    private fun handler(labelsJson: String? = null): AdbAdapterHandler {
        val labelsFile: File? = labelsJson?.let { json -> File(tempDir, "labels.json").apply { writeText(json) } }
        return AdbAdapterHandler(
            adbService = AdbService(commandRunner = { command, _ -> adb.answer(command) }),
            leaseManager = leaseManager,
            labels = AdbDeviceLabels(labelsFile)
        )
    }

    private fun request(
        count: Int,
        deviceIds: List<String> = emptyList(),
        excludeDeviceIds: List<String> = emptyList(),
        labels: Map<String, String> = emptyMap(),
        sessionId: String? = null
    ) = AcquireRequest(
        count = count,
        apiLevel = "34",
        ttlSeconds = 600,
        deviceIds = deviceIds,
        excludeDeviceIds = excludeDeviceIds,
        labels = labels,
        sessionId = sessionId
    )

    private fun pixel(id: String, state: String, leaseId: String? = null, reason: String? = null) = AdapterDevice(
        id = id,
        deviceType = DEVICE_TYPE_PHYSICAL,
        state = state,
        apiLevel = "34",
        manufacturer = "Google",
        model = "Pixel 8",
        abi = "arm64-v8a",
        leaseId = leaseId,
        labels = mapOf("rack" to "a"),
        metadata = reason?.let { value -> mapOf("reason" to value) }.orEmpty()
    )

    private fun testEnv() = AdapterEnv(
        port = 7037,
        advertisedAdbPort = 5037,
        secret = "secret",
        accessMode = ACCESS_EXPOSURE_DIRECT_TCP,
        accessAuthType = ACCESS_AUTH_NETWORK,
        accessScope = "private-network"
    )
}

/** Answers `adb devices` and `getprop` for a changeable set of devices, each a booted Pixel 8 on API 34. */
private class FakeAdb {
    private val states: MutableMap<String, String> = linkedMapOf()

    fun attach(vararg serials: String) {
        serials.forEach { serial -> states[serial] = "device" }
    }

    fun attachUnavailable(serial: String, state: String) {
        states[serial] = state
    }

    fun detach(serial: String) {
        states.remove(serial)
    }

    fun answer(command: List<String>): CommandResult {
        if (command == listOf("adb", "devices")) {
            val lines: List<String> = listOf("List of devices attached") + states.map { (serial, state) -> "$serial\t$state" }
            return CommandResult(0, lines.joinToString("\n"), true)
        }
        val serial: String? = command.getOrNull(2)
        if (command == listOf("adb", "-s", serial, "shell", "getprop") && states[serial] == "device") {
            return CommandResult(0, BOOTED_PIXEL_PROPERTIES, true)
        }
        return CommandResult(1, "unexpected command: $command", false)
    }
}

private class SequentialPortProxyController : AdbProxyController {
    private var nextPort: Int = 7600

    override fun startDeviceProxy(leaseId: String, device: AdbPhysicalDevice, preferredPort: Int?): Int = preferredPort ?: nextPort++

    override fun stopLease(leaseId: String) = Unit
}

private val BOOTED_PIXEL_PROPERTIES: String = """
    [sys.boot_completed]: [1]
    [ro.build.version.sdk]: [34]
    [ro.product.manufacturer]: [Google]
    [ro.product.model]: [Pixel 8]
    [ro.product.cpu.abi]: [arm64-v8a]
""".trimIndent()
