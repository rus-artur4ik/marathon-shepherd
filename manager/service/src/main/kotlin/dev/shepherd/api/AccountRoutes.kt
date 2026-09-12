package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.api.dto.toDto
import dev.shepherd.infra.audit.AuditQuery
import dev.shepherd.protocol.AuditPage
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val DEFAULT_AUDIT_PAGE: Int = 100
private const val MAX_AUDIT_PAGE: Int = 500

fun Route.accountRoutes(services: ManagerServices) {
    /** Who the key belongs to, its limits and what it currently holds. Any role. */
    get("/api/v1/me") {
        call.respond(call.shepherdApi(services).whoAmI())
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
        val records = services.auditStore.query(query)
        call.respond(
            AuditPage(
                entries = records.map { record -> record.toDto() },
                nextBefore = if (records.size == limit) records.last().id else null
            )
        )
    }
}
