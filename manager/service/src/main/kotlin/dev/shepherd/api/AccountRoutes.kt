package dev.shepherd.api

import dev.shepherd.api.dto.toDto
import dev.shepherd.domain.SessionManager
import dev.shepherd.infra.audit.AuditQuery
import dev.shepherd.infra.audit.AuditStore
import dev.shepherd.protocol.AuditPage
import dev.shepherd.protocol.UsageDto
import dev.shepherd.protocol.WhoAmIResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val DEFAULT_AUDIT_PAGE: Int = 100
private const val MAX_AUDIT_PAGE: Int = 500

fun Route.accountRoutes(sessionManager: SessionManager, auditStore: AuditStore) {
    /** Who the key belongs to, its limits and what it currently holds. Any role. */
    get("/api/v1/me") {
        val actor = call.actor()
        val usage = sessionManager.usageOf(actor.id)
        call.respond(
            WhoAmIResponse(
                id = actor.id,
                name = actor.name,
                role = actor.role.wireName,
                quota = actor.quota.toDto(),
                usage = UsageDto(activeSessions = usage.activeSessions, devices = usage.devices)
            )
        )
    }

    /** The audit log, newest first. Admins see everything; everyone else only their own entries. */
    get("/api/v1/audit") {
        val actor = call.actor().requireRole(*READER_ROLES)
        val parameters = call.request.queryParameters
        val limit: Int = parameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_AUDIT_PAGE) ?: DEFAULT_AUDIT_PAGE
        val query = AuditQuery(
            actorId = if (actor.isAdmin) null else actor.id,
            actorName = if (actor.isAdmin) parameters["actor"] else null,
            actionPrefix = parameters["action"],
            target = parameters["target"],
            before = parameters["before"]?.toLongOrNull(),
            limit = limit
        )
        val records = auditStore.query(query)
        call.respond(
            AuditPage(
                entries = records.map { record -> record.toDto() },
                nextBefore = if (records.size == limit) records.last().id else null
            )
        )
    }
}
