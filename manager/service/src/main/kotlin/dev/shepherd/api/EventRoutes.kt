package dev.shepherd.api

import dev.shepherd.domain.events.EventBus
import dev.shepherd.domain.events.ShepherdEvent
import dev.shepherd.domain.metrics.ManagerMetrics
import dev.shepherd.protocol.EventDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.Writer
import java.util.concurrent.atomic.AtomicInteger

private const val KEEP_ALIVE_MS: Long = 15_000L
private const val SUBSCRIBER_BUFFER: Int = 256
private const val RECONNECT_DELAY_MS: Long = 3_000L

/** SSE `data` must be one line, so events are encoded compactly, unlike the pretty-printed API. */
private val EventJson = Json { encodeDefaults = false }

/**
 * `GET /api/v1/events`: session, device and provider events as server-sent events.
 *
 * `types=session.,provider.up` filters by prefix. A client reconnecting with
 * `Last-Event-ID` first receives the buffered events it missed, then the live stream.
 * A comment line every 15 s keeps proxies from closing an idle connection.
 */
fun Route.eventRoutes(eventBus: EventBus, metrics: ManagerMetrics) {
    val subscribers = AtomicInteger()

    get("/api/v1/events") {
        // Checked before the stream starts, so a missing role is a proper 403.
        call.actor().requireRole(*READER_ROLES)
        val prefixes: List<String> = call.request.queryParameters["types"]
            ?.split(',')
            ?.map { type -> type.trim().removeSuffix("*") }
            ?.filter { type -> type.isNotEmpty() }
            .orEmpty()
        val resumeAfter: Long? = (call.request.headers["Last-Event-ID"] ?: call.request.queryParameters["lastEventId"])
            ?.trim()
            ?.toLongOrNull()

        call.response.header(HttpHeaders.CacheControl, "no-cache")
        // Tell nginx-style proxies not to buffer the stream.
        call.response.header("X-Accel-Buffering", "no")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            metrics.eventSubscribers(subscribers.incrementAndGet())
            try {
                coroutineScope {
                    val live = Channel<ShepherdEvent>(SUBSCRIBER_BUFFER, BufferOverflow.DROP_OLDEST)
                    val subscribed = CompletableDeferred<Unit>()
                    val collector = launch {
                        eventBus.live
                            .onSubscription { subscribed.complete(Unit) }
                            .collect { event -> live.trySend(event) }
                    }
                    // Subscribe before reading the backlog so nothing published in between is lost.
                    subscribed.await()
                    write("retry: $RECONNECT_DELAY_MS\n\n")
                    var lastSent: Long = resumeAfter ?: eventBus.latestId()
                    if (resumeAfter != null) {
                        for (event in eventBus.since(resumeAfter)) {
                            if (accepts(prefixes, event)) writeEvent(event)
                            lastSent = event.id
                        }
                    }
                    flush()
                    try {
                        while (true) {
                            val event: ShepherdEvent? = withTimeoutOrNull(KEEP_ALIVE_MS) { live.receive() }
                            if (event == null) {
                                write(": keep-alive\n\n")
                                flush()
                                continue
                            }
                            if (event.id <= lastSent) continue
                            lastSent = event.id
                            if (accepts(prefixes, event)) {
                                writeEvent(event)
                                flush()
                            }
                        }
                    } finally {
                        collector.cancel()
                    }
                }
            } finally {
                metrics.eventSubscribers(subscribers.decrementAndGet())
            }
        }
    }
}

private fun accepts(prefixes: List<String>, event: ShepherdEvent): Boolean =
    prefixes.isEmpty() || prefixes.any { prefix -> event.type.startsWith(prefix) }

private fun Writer.writeEvent(event: ShepherdEvent) {
    val payload = EventDto(id = event.id, type = event.type, at = event.at.toString(), data = event.data)
    write("id: ${event.id}\nevent: ${event.type}\ndata: ${EventJson.encodeToString(EventDto.serializer(), payload)}\n\n")
}
