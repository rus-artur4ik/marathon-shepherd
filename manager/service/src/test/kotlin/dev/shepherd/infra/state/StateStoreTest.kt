package dev.shepherd.infra.state

import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.*

class StateStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun createStore(): StateStore {
        return StateStore(File(tempDir, "test-${System.nanoTime()}.db").absolutePath)
    }

    private fun createSession(
        id: String = "sess_test1",
        status: SessionStatus = SessionStatus.READY,
        expiresAt: Instant = Instant.now().plusSeconds(3600)
    ): Session {
        val createdAt = Instant.now()
        return Session(
            id = id,
            status = status,
            requestedDevices = 3,
            allocatedDevices = 3,
            api = "34",
            deviceType = null,
            adbServers = listOf(AdbServer("192.168.1.10", 5037)),
            createdAt = createdAt,
            expiresAt = expiresAt,
            lastHeartbeatAt = createdAt,
            releasedAt = null
        )
    }

    @Test
    fun `should save and retrieve session`() = runTest {
        val store = createStore()
        val session = createSession()

        store.saveSession(session)
        val retrieved = store.getSession("sess_test1")

        assertNotNull(retrieved)
        assertEquals("sess_test1", retrieved.id)
        assertEquals(SessionStatus.READY, retrieved.status)
        assertEquals(3, retrieved.requestedDevices)
        assertEquals(3, retrieved.allocatedDevices)
        assertEquals("34", retrieved.api)
        assertEquals(1, retrieved.adbServers.size)
        assertEquals("192.168.1.10", retrieved.adbServers[0].host)
    }

    @Test
    fun `should return null for nonexistent session`() = runTest {
        val store = createStore()
        assertNull(store.getSession("nonexistent"))
    }

    @Test
    fun `should update session status`() = runTest {
        val store = createStore()
        store.saveSession(createSession())

        store.updateSessionStatus("sess_test1", SessionStatus.RELEASED)

        val updated = store.getSession("sess_test1")
        assertNotNull(updated)
        assertEquals(SessionStatus.RELEASED, updated.status)
        assertNotNull(updated.releasedAt)
    }

    @Test
    fun `should list active sessions`() = runTest {
        val store = createStore()
        store.saveSession(createSession(id = "sess_active1"))
        store.saveSession(createSession(id = "sess_active2"))
        store.saveSession(createSession(id = "sess_released", status = SessionStatus.RELEASED))

        val active = store.listSessions()

        assertEquals(2, active.size)
        assertTrue(active.all { it.status == SessionStatus.READY })
    }

    @Test
    fun `should list sessions filtered by status`() = runTest {
        val store = createStore()
        store.saveSession(createSession(id = "sess_ready", status = SessionStatus.READY))
        store.saveSession(createSession(id = "sess_pending", status = SessionStatus.PENDING))
        store.saveSession(createSession(id = "sess_released", status = SessionStatus.RELEASED))

        val readySessions = store.listSessions(SessionStatus.READY)

        assertEquals(1, readySessions.size)
        assertEquals("sess_ready", readySessions.first().id)
    }

    @Test
    fun `should list expired sessions`() = runTest {
        val store = createStore()
        store.saveSession(createSession(id = "sess_fresh", expiresAt = Instant.now().plusSeconds(3600)))
        store.saveSession(createSession(id = "sess_expired", expiresAt = Instant.now().minusSeconds(60)))
        store.saveSession(
            createSession(
                id = "sess_pending_expired",
                status = SessionStatus.PENDING,
                expiresAt = Instant.now().minusSeconds(120)
            )
        )
        store.saveSession(
            createSession(
                id = "sess_failed_expired",
                status = SessionStatus.FAILED,
                expiresAt = Instant.now().minusSeconds(180)
            )
        )

        val expired = store.listExpiredSessions()

        assertEquals(3, expired.size)
        assertTrue(expired.any { it.id == "sess_expired" })
        assertTrue(expired.any { it.id == "sess_pending_expired" })
        assertTrue(expired.any { it.id == "sess_failed_expired" })
    }

    @Test
    fun `should update full session payload`() = runTest {
        val store = createStore()
        store.saveSession(createSession())

        val updatedSession = createSession(id = "sess_test1").copy(
            status = SessionStatus.READY,
            allocatedDevices = 5,
            adbServers = listOf(
                AdbServer("192.168.1.20", 5037),
                AdbServer("192.168.1.21", 5037)
            )
        )

        store.updateSession(updatedSession)

        val actualSession = store.getSession("sess_test1")

        assertNotNull(actualSession)
        assertEquals(5, actualSession.allocatedDevices)
        assertEquals(2, actualSession.adbServers.size)
    }

    @Test
    fun `should save and retrieve session leases`() = runTest {
        val store = createStore()
        store.saveSession(createSession())

        store.saveSessionLease("sess_test1", "rack-1", "lease_abc", 2)
        store.saveSessionLease("sess_test1", "farm-1", "lease_def", 3)

        val leases = store.getSessionLeases("sess_test1")

        assertEquals(2, leases.size)
        assertEquals(2, leases.first { it.providerName == "rack-1" }.count)
        assertEquals("lease_def", leases.first { it.providerName == "farm-1" }.leaseId)
    }

    @Test
    fun `should delete session and related leases`() = runTest {
        val store = createStore()
        store.saveSession(createSession())
        store.saveSessionLease("sess_test1", "rack-1", "lease_abc", 3)

        store.deleteSession("sess_test1")

        assertNull(store.getSession("sess_test1"))
        assertTrue(store.getSessionLeases("sess_test1").isEmpty())
    }
}
