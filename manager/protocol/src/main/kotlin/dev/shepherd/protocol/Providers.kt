package dev.shepherd.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A provider the manager knows, whether configured in msh.yaml or self-registered. */
@Serializable
data class ProviderInfoDto(
    val name: String,
    /** `static` (msh.yaml) or `registered` (an adapter that registered itself). */
    val source: String,
    val url: String,
    val accessHost: String? = null,
    val adapterType: String? = null,
    /** Health at the last fleet poll; absent before the first poll. */
    val healthy: Boolean? = null,
    /** False for a registration whose heartbeat lapsed; it gets no new sessions. */
    val active: Boolean,
    val registeredBy: String? = null,
    val registeredAt: String? = null,
    val lastSeenAt: String? = null
)

/** One entry of `GET /api/v1/events`, sent as the SSE `data` field. */
@Serializable
data class EventDto(
    val id: Long,
    val type: String,
    val at: String,
    val data: JsonObject
)
