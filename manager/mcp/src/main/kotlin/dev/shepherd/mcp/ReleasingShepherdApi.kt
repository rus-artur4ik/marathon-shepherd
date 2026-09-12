package dev.shepherd.mcp

import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.ShepherdApi
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the sessions created through it, so a local MCP server can release them when its
 * agent goes away instead of leaving devices held until the sessions time out.
 */
class ReleasingShepherdApi(private val delegate: ShepherdApi) : ShepherdApi by delegate {
    private val created: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun createSession(request: CreateSessionRequest): SessionResponse =
        delegate.createSession(request).also { session -> created += session.id }

    override suspend fun releaseSession(id: String) {
        delegate.releaseSession(id)
        created -= id
    }

    /** Releases every session created here and not yet released; returns how many it released. */
    suspend fun releaseAll(): Int {
        var released = 0
        for (id in created.toList()) {
            try {
                delegate.releaseSession(id)
                released += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Already expired or released elsewhere; nothing left to give back.
            }
            created -= id
        }
        return released
    }
}
