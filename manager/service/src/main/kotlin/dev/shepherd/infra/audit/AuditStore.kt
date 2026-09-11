package dev.shepherd.infra.audit

import dev.shepherd.domain.audit.AuditOutcome
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Actor
import dev.shepherd.infra.db.ShepherdDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

object AuditLog : Table("audit_log") {
    val id = long("id").autoIncrement()
    val at = timestamp("at").index()
    val actorId = varchar("actor_id", 64).nullable()
    val actorName = varchar("actor_name", 128)
    val action = varchar("action", 64).index()
    val target = varchar("target", 200).nullable()
    val outcome = varchar("outcome", 16)
    val details = text("details").nullable()
    val origin = varchar("origin", 128).nullable()

    override val primaryKey = PrimaryKey(id)
}

data class AuditRecord(
    val id: Long,
    val at: Instant,
    val actorId: String?,
    val actorName: String,
    val action: String,
    val target: String?,
    val outcome: String,
    val details: Map<String, String>,
    val origin: String?
)

data class AuditQuery(
    val actorId: String? = null,
    val actorName: String? = null,
    /** Matches actions starting with this, e.g. `session.` or `client.revoke`. */
    val actionPrefix: String? = null,
    val target: String? = null,
    /** Only entries older than this id, for paging backwards. */
    val before: Long? = null,
    val limit: Int = 100
)

class AuditStore(private val db: ShepherdDatabase) {
    private val json = Json

    suspend fun append(
        at: Instant,
        actorId: String?,
        actorName: String,
        action: String,
        target: String?,
        outcome: String,
        details: Map<String, String>,
        origin: String?
    ): Long = db.tx {
        AuditLog.insert { row ->
            row[AuditLog.at] = at
            row[AuditLog.actorId] = actorId
            row[AuditLog.actorName] = actorName
            row[AuditLog.action] = action
            row[AuditLog.target] = target?.take(TARGET_LIMIT)
            row[AuditLog.outcome] = outcome
            row[AuditLog.details] = details.takeIf { map -> map.isNotEmpty() }?.let { map -> json.encodeToString(map) }
            row[AuditLog.origin] = origin?.take(ORIGIN_LIMIT)
        }[AuditLog.id]
    }

    /** Newest first. */
    suspend fun query(query: AuditQuery): List<AuditRecord> = db.tx {
        val conditions: List<Op<Boolean>> = buildList {
            query.actorId?.let { value -> add(AuditLog.actorId eq value) }
            query.actorName?.let { value -> add(AuditLog.actorName eq value) }
            query.actionPrefix?.let { value -> add(AuditLog.action like "$value%") }
            query.target?.let { value -> add(AuditLog.target eq value) }
            query.before?.let { value -> add(AuditLog.id less value) }
        }
        val base = AuditLog.selectAll()
        val filtered = if (conditions.isEmpty()) base else base.where { conditions.reduce { left, right -> left and right } }
        filtered.orderBy(AuditLog.id to SortOrder.DESC).limit(query.limit).map(::toRecord)
    }

    suspend fun pruneBefore(cutoff: Instant): Int = db.tx {
        AuditLog.deleteWhere { AuditLog.at less cutoff }
    }

    private fun toRecord(row: ResultRow): AuditRecord = AuditRecord(
        id = row[AuditLog.id],
        at = row[AuditLog.at],
        actorId = row[AuditLog.actorId],
        actorName = row[AuditLog.actorName],
        action = row[AuditLog.action],
        target = row[AuditLog.target],
        outcome = row[AuditLog.outcome],
        details = row[AuditLog.details]?.let { text -> json.decodeFromString<Map<String, String>>(text) } ?: emptyMap(),
        origin = row[AuditLog.origin]
    )

    private companion object {
        const val TARGET_LIMIT = 200
        const val ORIGIN_LIMIT = 128
    }
}

/** [AuditTrail] backed by [AuditStore]; a failed write is logged, never thrown. */
class StoreAuditTrail(
    private val store: AuditStore,
    private val clock: Clock = Clock.systemUTC()
) : AuditTrail {
    private val logger = LoggerFactory.getLogger(StoreAuditTrail::class.java)

    override suspend fun record(actor: Actor, action: String, target: String?, outcome: AuditOutcome, details: Map<String, String>) {
        try {
            store.append(clock.instant(), actor.id, actor.name, action, target, outcome.wireName, details, actor.origin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Could not record audit entry {} {}: {}", action, target ?: "", error.message)
        }
    }
}
