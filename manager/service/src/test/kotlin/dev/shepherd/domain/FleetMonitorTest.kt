package dev.shepherd.domain

import dev.shepherd.adapter.api.AdapterAccess
import dev.shepherd.adapter.api.AdapterCapabilities
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.domain.model.ActiveSessionCounts
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.provider.AcquireResult
import dev.shepherd.domain.provider.DevicePoolStatus
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.FakeDeviceProvider
import dev.shepherd.domain.provider.FakeProviderCatalog
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FleetMonitorTest {

    @Test
    fun `should aggregate pool status from all providers`() = runTest {
        val allocator = FleetMonitor(
            FakeProviderCatalog(
                active = listOf(
                    FakeDeviceProvider("rack-1", totalDevices = 4),
                    FakeDeviceProvider("farm-1", totalDevices = 10)
                )
            )
        )

        val statuses = allocator.getProviderStatuses()

        assertEquals(2, statuses.size)
        assertEquals(4, statuses.first { it.name == "rack-1" }.pool.total)
        assertEquals(10, statuses.first { it.name == "farm-1" }.pool.total)
    }

    @Test
    fun `should report unhealthy provider`() = runTest {
        val allocator = FleetMonitor(
            FakeProviderCatalog(
                active = listOf(
                    FakeDeviceProvider("rack-1", totalDevices = 4),
                    FakeDeviceProvider("broken-farm", totalDevices = 0, shouldFail = true)
                )
            )
        )

        val statuses = allocator.getProviderStatuses()

        assertTrue(statuses.first { it.name == "rack-1" }.isHealthy)
        assertFalse(statuses.first { it.name == "broken-farm" }.isHealthy)
    }

    @Test
    fun `should sum total available and busy across providers`() = runTest {
        val allocator = FleetMonitor(
            FakeProviderCatalog(
                active = listOf(
                    FakeDeviceProvider("rack-1", totalDevices = 3),
                    FakeDeviceProvider("rack-2", totalDevices = 5)
                )
            )
        )

        val statuses = allocator.getProviderStatuses()
        val totalAvailable = statuses.sumOf { it.pool.available }

        assertEquals(8, totalAvailable)
    }

    @Test
    fun `snapshot is reused while it is fresh`() = runTest {
        val provider = CountingProvider("rack-1")
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val monitor = FleetMonitor(FakeProviderCatalog(active = listOf(provider)), clock = clock)

        monitor.snapshot(Duration.ofSeconds(30))
        clock.now = clock.now.plusSeconds(10)
        monitor.snapshot(Duration.ofSeconds(30))

        assertEquals(1, provider.statusCalls)
    }

    @Test
    fun `stale snapshot triggers a new poll`() = runTest {
        val provider = CountingProvider("rack-1")
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val monitor = FleetMonitor(FakeProviderCatalog(active = listOf(provider)), clock = clock)

        monitor.snapshot(Duration.ofSeconds(30))
        clock.now = clock.now.plusSeconds(31)
        monitor.snapshot(Duration.ofSeconds(30))

        assertEquals(2, provider.statusCalls)
    }

    @Test
    fun `hung provider is reported unhealthy once the poll timeout passes`() = runTest {
        // Before the snapshot existed, one hung adapter stalled every /health caller.
        val monitor = FleetMonitor(
            FakeProviderCatalog(active = listOf(CountingProvider("healthy"), HangingProvider("hung"))),
            pollTimeout = { Duration.ofMillis(200) }
        )

        val statuses = monitor.refresh().providers.associateBy { it.name }

        assertTrue(statuses.getValue("healthy").isHealthy)
        assertFalse(statuses.getValue("hung").isHealthy)
        assertContains(statuses.getValue("hung").error.orEmpty(), "timed out")
    }

    @Test
    fun `snapshot carries the active session counts`() = runTest {
        val monitor = FleetMonitor(
            FakeProviderCatalog(active = emptyList()),
            sessionCounts = { ActiveSessionCounts(pending = 2, ready = 1, allocatedDevices = 3) }
        )

        val sessions = monitor.refresh().sessions

        assertEquals(ActiveSessionCounts(pending = 2, ready = 1, allocatedDevices = 3), sessions)
    }
}

private class MutableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

private open class CountingProvider(override val name: String) : DeviceProvider {
    var statusCalls: Int = 0

    override val adbServer: AdbServer = AdbServer("127.0.0.1", 5037)
    override val access: AdapterAccess = AdapterAccess()
    override val capabilities: AdapterCapabilities = AdapterCapabilities()
    override val inventory: List<AdapterDeviceProfile> = emptyList()

    override suspend fun queryDevices(): DevicePoolStatus {
        statusCalls += 1
        return DevicePoolStatus(available = 1, busy = 0, total = 1)
    }

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult =
        AcquireResult(leaseId = "", acquiredCount = 0)

    override fun canAllocateApiLevel(apiLevel: String): Boolean = true
    override fun supportsDeviceType(deviceType: String): Boolean = true
    override suspend fun release(leaseId: String) = Unit
    override suspend fun isHealthy(): Boolean = true
}

private class HangingProvider(name: String) : CountingProvider(name) {
    override suspend fun isHealthy(): Boolean = awaitCancellation()
}
