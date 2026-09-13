package dev.shepherd.protocol

import kotlinx.serialization.Serializable

/** Client limits; a null field is not limited. */
@Serializable
data class QuotaDto(
    val maxDevices: Int? = null,
    val maxSessionLifetimeSeconds: Long? = null,
    val maxPriority: Int? = null
)

@Serializable
data class ClientDto(
    val id: String,
    val name: String,
    /** admin, user, viewer or provider. */
    val role: String,
    /** First characters of the key, to tell keys apart; the key itself is shown only once. */
    val keyPrefix: String,
    val description: String? = null,
    /** Limits set on this client explicitly. */
    val quota: QuotaDto = QuotaDto(),
    /** Limits in force, with the configured defaults applied. */
    val effectiveQuota: QuotaDto = QuotaDto(),
    val active: Boolean,
    val createdAt: String,
    val createdBy: String? = null,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null
)

@Serializable
data class CreateClientRequest(
    val name: String,
    val role: String = "user",
    val description: String? = null,
    val quota: QuotaDto = QuotaDto()
)

@Serializable
data class UpdateClientRequest(
    val role: String? = null,
    val description: String? = null,
    /** Replaces the client's whole quota when present; null fields inside mean "not limited". */
    val quota: QuotaDto? = null
)

@Serializable
data class ClientKeyResponse(
    val client: ClientDto,
    /** Shown exactly once. Store it now; only its prefix can be seen later. */
    val apiKey: String
)

@Serializable
data class UsageDto(
    val activeSessions: Int,
    /** Devices held by ready sessions plus devices requested by queued ones. */
    val devices: Int
)

@Serializable
data class WhoAmIResponse(
    val id: String,
    val name: String,
    val role: String,
    val quota: QuotaDto,
    val usage: UsageDto,
    /** `user` for a person (signed in or with a personal token), `client` for an API client. */
    val kind: String = "client"
)

@Serializable
data class AuditEntryDto(
    val id: Long,
    val at: String,
    val actor: String,
    val actorId: String? = null,
    val action: String,
    val target: String? = null,
    /** success, denied or failed. */
    val outcome: String,
    val details: Map<String, String> = emptyMap(),
    val origin: String? = null
)

@Serializable
data class AuditPage(
    val entries: List<AuditEntryDto>,
    /** Pass as `before` to fetch the next, older page; absent on the last page. */
    val nextBefore: Long? = null
)
