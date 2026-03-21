package dev.shepherd.domain

import dev.shepherd.domain.provider.FakeDeviceProvider
import dev.shepherd.domain.provider.FakeProviderCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceAllocatorTest {

    @Test
    fun `should aggregate pool status from all providers`() = runTest {
        val allocator = DeviceAllocator(
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
        val allocator = DeviceAllocator(
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
        val allocator = DeviceAllocator(
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
}
