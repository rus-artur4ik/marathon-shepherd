package dev.shepherd.domain

import dev.shepherd.adapter.api.DEVICE_STATE_BUSY
import dev.shepherd.adapter.api.FEATURE_DEVICE_SELECTION
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.AccessDeniedException
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.domain.events.EventPublisher
import dev.shepherd.domain.events.EventTypes
import dev.shepherd.domain.model.QueuePolicy
import dev.shepherd.domain.model.SessionOptions
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.provider.FakeProviderCatalog
import dev.shepherd.domain.provider.ListingProvider
import dev.shepherd.domain.provider.ListingProvider.Companion.device
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionLifecycleTest {

    @TempDir
    lateinit var tempDir: File

    private val alice = Actor(id = "cli_alice", name = "alice", role = Role.USER)
    private val bob = Actor(id = "cli_bob", name = "bob", role = Role.USER)

    @Test
    fun `extend renews every lease and moves the expiry`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A")))
        val manager = fixture(rack, "extend").manager
        val session = manager.createSession(1, "34", 60, actor = alice)

        val extended = manager.extendSession(session.id, ttlSeconds = 3_600, actor = alice)

        assertEquals(listOf("lease-1"), rack.renewed.map { (leaseId, _) -> leaseId })
        assertTrue(extended.expiresAt.isAfter(session.expiresAt))
    }

    @Test
    fun `extend stays within the lifetime cap`() = runTest {
        val capped = alice.copy(quota = ClientQuota(maxSessionLifetimeSeconds = 600))
        val manager = fixture(ListingProvider("rack", listOf(device("A"))), "cap").manager
        val session = manager.createSession(1, "34", 600, actor = capped)

        val extended = manager.extendSession(session.id, ttlSeconds = 7_200, actor = capped)

        assertEquals(session.createdAt.plusSeconds(600), extended.expiresAt)
    }

    @Test
    fun `a provider that cannot renew leases blocks the extension`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A")), features = listOf(FEATURE_DEVICE_SELECTION))
        val manager = fixture(rack, "no-renew").manager
        val session = manager.createSession(1, "34", 60, actor = alice)

        assertFailsWith<ConflictException> { manager.extendSession(session.id, 3_600, alice) }
    }

    @Test
    fun `only the owner or an admin can extend or heartbeat`() = runTest {
        val manager = fixture(ListingProvider("rack", listOf(device("A"))), "owner").manager
        val session = manager.createSession(1, "34", 60, actor = alice)

        assertFailsWith<AccessDeniedException> { manager.extendSession(session.id, 600, bob) }
        assertFailsWith<AccessDeniedException> { manager.heartbeat(session.id, bob) }
        manager.extendSession(session.id, 600, Actor.SYSTEM)
    }

    @Test
    fun `idle sessions are released and announced, while heartbeats keep them`() = runTest {
        val rack = ListingProvider("rack", listOf(device("A"), device("B")))
        val fixture = fixture(rack, "idle")
        val idle = fixture.manager.createSession(1, "34", 3_600, actor = alice, options = SessionOptions(idleTimeoutSeconds = 30))
        val alive = fixture.manager.createSession(1, "34", 3_600, actor = alice, options = SessionOptions(idleTimeoutSeconds = 30))
        fixture.stateStore.touchSessionHeartbeat(idle.id, Instant.now().minusSeconds(120))
        fixture.stateStore.touchSessionHeartbeat(alive.id, Instant.now().minusSeconds(120))
        fixture.manager.heartbeat(alive.id, alice)

        fixture.manager.cleanupExpiredSessions()

        assertEquals(SessionStatus.EXPIRED, fixture.stateStore.getSession(idle.id)?.status)
        assertEquals(SessionStatus.READY, fixture.stateStore.getSession(alive.id)?.status)
        assertEquals(listOf("lease-1"), rack.released)
        assertTrue(fixture.events.types.contains(EventTypes.SESSION_EXPIRED))
    }

    @Test
    fun `the priority policy lets higher priority overtake`() = runTest {
        val busyRack = ListingProvider("rack", listOf(device("A", state = DEVICE_STATE_BUSY)))
        val manager = fixture(busyRack, "priority", QueuePolicy.PRIORITY).manager

        val low = manager.createSession(1, "34", 600, options = SessionOptions(priority = 0))
        val high = manager.createSession(1, "34", 600, options = SessionOptions(priority = 5))

        assertEquals(1, manager.getQueuePosition(high.id))
        assertEquals(2, manager.getQueuePosition(low.id))
    }

    @Test
    fun `the fifo policy ignores priority`() = runTest {
        val busyRack = ListingProvider("rack", listOf(device("A", state = DEVICE_STATE_BUSY)))
        val manager = fixture(busyRack, "fifo", QueuePolicy.FIFO).manager

        val low = manager.createSession(1, "34", 600, options = SessionOptions(priority = 0))
        val high = manager.createSession(1, "34", 600, options = SessionOptions(priority = 5))

        assertEquals(1, manager.getQueuePosition(low.id))
        assertEquals(2, manager.getQueuePosition(high.id))
    }

    @Test
    fun `users cannot raise priority without a quota for it`() = runTest {
        val manager = fixture(ListingProvider("rack", listOf(device("A"))), "priority-quota").manager

        assertFailsWith<AccessDeniedException> {
            manager.createSession(1, "34", 60, actor = alice, options = SessionOptions(priority = 1))
        }
        manager.createSession(1, "34", 60, actor = alice.copy(quota = ClientQuota(maxPriority = 3)), options = SessionOptions(priority = 3))
    }

    @Test
    fun `session events describe the lifecycle in order`() = runTest {
        val fixture = fixture(ListingProvider("rack", listOf(device("A"))), "events")
        val session = fixture.manager.createSession(1, "34", 60, actor = alice)

        fixture.manager.releaseSession(session.id, alice)

        assertEquals(
            listOf(EventTypes.SESSION_CREATED, EventTypes.SESSION_READY, EventTypes.SESSION_RELEASED),
            fixture.events.types
        )
    }

    private class Fixture(val manager: SessionManager, val stateStore: StateStore, val events: RecordingEvents)

    private fun fixture(rack: ListingProvider, name: String, policy: QueuePolicy = QueuePolicy.FIFO): Fixture {
        val stateStore = StateStore(File(tempDir, "$name.db").absolutePath)
        val events = RecordingEvents()
        val manager = SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(rack)),
            stateStore = stateStore,
            events = events,
            queuePolicy = { policy }
        )
        return Fixture(manager, stateStore, events)
    }
}

internal class RecordingEvents : EventPublisher {
    val published = mutableListOf<Pair<String, JsonObject>>()
    val types: List<String> get() = published.map { (type, _) -> type }

    override fun publish(type: String, data: JsonObject) {
        published += type to data
    }
}
