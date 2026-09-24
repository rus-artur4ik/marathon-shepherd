package dev.shepherd.infra.metrics

import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionHistoryMetricsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `empty table reports zeros and no last request`() = runTest {
        val store = StateStore(File(tempDir, "empty.db").absolutePath)

        val history = store.sessionHistory()

        assertNull(history.lastRequestedAt)
        assertTrue(history.byStatus.values.all { count -> count == 0 })
        assertEquals(SessionStatus.entries.toSet(), history.byStatus.keys)
    }

    @Test
    fun `history counts statuses and finds the newest request`() = runTest {
        val store = StateStore(File(tempDir, "history.db").absolutePath)
        val older = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val newer = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        store.saveSession(session("s1", SessionStatus.RELEASED, older))
        store.saveSession(session("s2", SessionStatus.FAILED, older))
        store.saveSession(session("s3", SessionStatus.RELEASED, newer))

        val history = store.sessionHistory()

        assertEquals(2, history.byStatus[SessionStatus.RELEASED])
        assertEquals(1, history.byStatus[SessionStatus.FAILED])
        assertEquals(0, history.byStatus[SessionStatus.READY])
        assertEquals(newer, history.lastRequestedAt)
    }

    @Test
    fun `history reaches the Prometheus scrape`() = runTest {
        val store = StateStore(File(tempDir, "scrape.db").absolutePath)
        val requested = Instant.parse("2026-09-20T10:00:00Z")
        store.saveSession(session("s1", SessionStatus.EXPIRED, requested))
        val metrics = MicrometerManagerMetrics()

        val before = metrics.registry.scrape()
        assertTrue(Regex("""msh_sessions_last_request_seconds 0(\.0)?""").containsMatchIn(before), before)
        assertTrue(Regex("""msh_sessions_stored\{status="released"\} 0(\.0)?""").containsMatchIn(before), before)

        metrics.historyObserved(store.sessionHistory())
        val after = metrics.registry.scrape()

        assertTrue(Regex("""msh_sessions_stored\{status="expired"\} 1(\.0)?""").containsMatchIn(after), after)
        assertTrue(Regex("""msh_sessions_last_request_seconds 1\.7898984E9""").containsMatchIn(after), after)
        // Five statuses, no more: the label set stays closed.
        assertEquals(5, after.lines().count { line -> line.startsWith("msh_sessions_stored{") })
    }

    private fun session(id: String, status: SessionStatus, createdAt: Instant): Session = Session(
        id = id,
        status = status,
        requestedDevices = 1,
        allocatedDevices = if (status == SessionStatus.READY) 1 else 0,
        api = "34",
        deviceType = null,
        adbServers = listOf(AdbServer("192.168.1.10", 5037)),
        createdAt = createdAt,
        expiresAt = createdAt.plusSeconds(3_600),
        lastHeartbeatAt = createdAt,
        releasedAt = if (status == SessionStatus.READY || status == SessionStatus.PENDING) null else createdAt.plusSeconds(600)
    )
}
