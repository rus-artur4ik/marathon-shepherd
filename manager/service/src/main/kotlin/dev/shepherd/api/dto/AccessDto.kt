package dev.shepherd.api.dto

import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.infra.audit.AuditRecord
import dev.shepherd.infra.auth.ClientRecord
import dev.shepherd.protocol.AuditEntryDto
import dev.shepherd.protocol.ClientDto
import dev.shepherd.protocol.QuotaDto

fun ClientQuota.toDto(): QuotaDto = QuotaDto(
    maxDevices = maxDevices,
    maxSessionLifetimeSeconds = maxSessionLifetimeSeconds,
    maxPriority = maxPriority
)

/** Throws [IllegalArgumentException] (400) for out-of-range values. */
fun QuotaDto.toDomain(): ClientQuota = ClientQuota(
    maxDevices = maxDevices,
    maxSessionLifetimeSeconds = maxSessionLifetimeSeconds,
    maxPriority = maxPriority
)

fun ClientRecord.toDto(effectiveQuota: ClientQuota): ClientDto = ClientDto(
    id = id,
    name = name,
    role = role.wireName,
    keyPrefix = keyPrefix,
    description = description,
    quota = quota.toDto(),
    effectiveQuota = effectiveQuota.toDto(),
    active = isActive,
    createdAt = createdAt.toString(),
    createdBy = createdBy,
    lastUsedAt = lastUsedAt?.toString(),
    revokedAt = revokedAt?.toString()
)

fun AuditRecord.toDto(): AuditEntryDto = AuditEntryDto(
    id = id,
    at = at.toString(),
    actor = actorName,
    actorId = actorId,
    action = action,
    target = target,
    outcome = outcome,
    details = details,
    origin = origin
)
