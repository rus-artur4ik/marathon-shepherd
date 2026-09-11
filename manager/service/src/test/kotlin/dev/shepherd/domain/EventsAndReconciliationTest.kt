package dev.shepherd.domain

import dev.shepherd.adapter.api.AdapterLease
import dev.shepherd.adapter.api.FEATURE_DEVICE_SELECTION
import dev.shepherd.domain.events.EventBus
import dev.shepherd.domain.provider.FakeProviderCatalog
import dev.shepherd.domain.provider.ListingProvider
import dev.shepherd.domain.provider.ListingProvider.Companion.device
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventsAndReconciliationTest {

    @Test
    fun `the event bus replays buffered events after a given id`() {
        val bus = EventBus(capacity = 3)
        repeat(5) { index -> bus.publish("test.$index", JsonObject(emptyMap())) }

        assertEquals(listOf(4L, 5L), bus.since(3).map { it.id })
        assertEquals(listOf(3L, 4L, 5L), bus.since(0).map { it.id }, "only the last 3 events are kept")
        assertEquals(5L, bus.latestId())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `live subscribers receive events in publication order`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<Long>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.live.take(3).toList(mutableListOf()).mapTo(received) { event -> event.id }
        }

        repeat(3) { index -> bus.publish("test.$index", JsonObject(emptyMap())) }
        collector.join()

        assertEquals(listOf(1L, 2L, 3L), received)
    }

    @Test
    fun `an orphaned lease is released on the second pass that sees it`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A"))).apply { extraLeases = listOf(AdapterLease("orphan-1")) }
        val reconciler = LeaseReconciler(FakeProviderCatalog(active = listOf(rack)), knownLeaseIds = { emptySet() })

        val firstPass = reconciler.reconcile()
        val secondPass = reconciler.reconcile()

        assertEquals(0, firstPass)
        assertEquals(1, secondPass)
        assertEquals(listOf("orphan-1"), rack.released)
    }

    @Test
    fun `a lease recorded between passes is spared`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A"))).apply { extraLeases = listOf(AdapterLease("late-1")) }
        var known = emptySet<String>()
        val reconciler = LeaseReconciler(FakeProviderCatalog(active = listOf(rack)), knownLeaseIds = { known })

        reconciler.reconcile()
        known = setOf("late-1")
        reconciler.reconcile()
        reconciler.reconcile()

        assertTrue(rack.released.isEmpty())
    }

    @Test
    fun `adapters that cannot list leases are left alone`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A")), features = listOf(FEATURE_DEVICE_SELECTION))
            .apply { extraLeases = listOf(AdapterLease("orphan-1")) }
        val reconciler = LeaseReconciler(FakeProviderCatalog(active = listOf(rack)), knownLeaseIds = { emptySet() })

        repeat(3) { reconciler.reconcile() }

        assertTrue(rack.released.isEmpty())
    }
}
