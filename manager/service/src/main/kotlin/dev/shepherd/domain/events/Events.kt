package dev.shepherd.domain.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Instant

/** Event types on `GET /api/v1/events`; clients filter by prefix, e.g. `session.`. */
object EventTypes {
    const val SESSION_CREATED = "session.created"
    const val SESSION_READY = "session.ready"
    const val SESSION_RELEASED = "session.released"
    const val SESSION_EXPIRED = "session.expired"
    const val SESSION_FAILED = "session.failed"
    const val SESSION_EXTENDED = "session.extended"
    const val DEVICE_MAINTENANCE = "device.maintenance"
    const val PROVIDER_UP = "provider.up"
    const val PROVIDER_DOWN = "provider.down"
    const val PROVIDER_REGISTERED = "provider.registered"
    const val PROVIDER_DEREGISTERED = "provider.deregistered"
    const val LEASE_RECLAIMED = "lease.reclaimed"
}

data class ShepherdEvent(
    val id: Long,
    val type: String,
    val at: Instant,
    val data: JsonObject
)

fun interface EventPublisher {
    fun publish(type: String, data: JsonObject)

    companion object {
        val NONE: EventPublisher = EventPublisher { _, _ -> }
    }
}

/**
 * The in-process stream behind `GET /api/v1/events`.
 *
 * The last [capacity] events are kept so a reconnecting client can resume from its
 * `Last-Event-ID`. Ids restart with the manager, which a client sees as a gap. Publishing
 * never blocks: a subscriber that falls behind loses its oldest undelivered events.
 */
class EventBus(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: Clock = Clock.systemUTC()
) : EventPublisher {
    private val lock = Any()
    private val recent = ArrayDeque<ShepherdEvent>()
    private var lastId: Long = 0
    private val flow = MutableSharedFlow<ShepherdEvent>(
        extraBufferCapacity = LIVE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Events as they happen; subscribe before reading [since] to avoid a gap. */
    val live: SharedFlow<ShepherdEvent> = flow.asSharedFlow()

    override fun publish(type: String, data: JsonObject) {
        // Emitting under the lock keeps the live stream in id order; tryEmit never suspends.
        synchronized(lock) {
            lastId += 1
            val event = ShepherdEvent(id = lastId, type = type, at = clock.instant(), data = data)
            recent.addLast(event)
            while (recent.size > capacity) {
                recent.removeFirst()
            }
            flow.tryEmit(event)
        }
    }

    /** Buffered events with an id above [afterId], oldest first. */
    fun since(afterId: Long): List<ShepherdEvent> = synchronized(lock) { recent.filter { event -> event.id > afterId } }

    fun latestId(): Long = synchronized(lock) { lastId }

    companion object {
        const val DEFAULT_CAPACITY: Int = 1_000
        private const val LIVE_BUFFER: Int = 1_024
    }
}
