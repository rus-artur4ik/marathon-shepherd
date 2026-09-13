package dev.shepherd.infra.auth

import dev.shepherd.infra.db.ShepherdDatabase
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Browser sessions. The cookie carries a random id; only its SHA-256 digest is stored, so a copy
 * of the database cannot be replayed as a session.
 */
object WebSessions : Table("web_sessions") {
    val idHash = varchar("id_hash", 64)
    val userId = varchar("user_id", 64).references(Users.id)
    val csrfToken = varchar("csrf_token", 64)
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at")

    /** The absolute end of the session, however active it is. */
    val expiresAt = timestamp("expires_at")
    val origin = varchar("origin", 128).nullable()
    val userAgent = varchar("user_agent", 256).nullable()

    override val primaryKey = PrimaryKey(idHash)
}

data class WebSessionRecord(
    val idHash: String,
    val userId: String,
    val csrfToken: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
    val origin: String?,
    val userAgent: String?
)

class WebSessionStore(private val db: ShepherdDatabase) {

    suspend fun insert(record: WebSessionRecord) = db.tx {
        WebSessions.insert { row ->
            row[idHash] = record.idHash
            row[userId] = record.userId
            row[csrfToken] = record.csrfToken
            row[createdAt] = record.createdAt
            row[lastSeenAt] = record.lastSeenAt
            row[expiresAt] = record.expiresAt
            row[origin] = record.origin?.take(ORIGIN_LIMIT)
            row[userAgent] = record.userAgent?.take(USER_AGENT_LIMIT)
        }
        Unit
    }

    suspend fun find(idHash: String): WebSessionRecord? = db.tx {
        WebSessions.selectAll().where { WebSessions.idHash eq idHash }.map(::toRecord).firstOrNull()
    }

    suspend fun touch(idHash: String, at: Instant) = db.tx {
        WebSessions.update({ WebSessions.idHash eq idHash }) { row -> row[lastSeenAt] = at }
        Unit
    }

    suspend fun delete(idHash: String) = db.tx {
        WebSessions.deleteWhere { WebSessions.idHash eq idHash }
        Unit
    }

    /** Signs the user out everywhere, except in the session [keepIdHash] when given. */
    suspend fun deleteForUser(userId: String, keepIdHash: String? = null): Int = db.tx {
        if (keepIdHash == null) {
            WebSessions.deleteWhere { WebSessions.userId eq userId }
        } else {
            WebSessions.deleteWhere { (WebSessions.userId eq userId) and (WebSessions.idHash neq keepIdHash) }
        }
    }

    /** Deletes sessions past their absolute end or idle since before [idleBefore]. */
    suspend fun deleteEnded(now: Instant, idleBefore: Instant): Int = db.tx {
        WebSessions.deleteWhere { (WebSessions.expiresAt less now) or (WebSessions.lastSeenAt less idleBefore) }
    }

    private fun toRecord(row: ResultRow): WebSessionRecord = WebSessionRecord(
        idHash = row[WebSessions.idHash],
        userId = row[WebSessions.userId],
        csrfToken = row[WebSessions.csrfToken],
        createdAt = row[WebSessions.createdAt],
        lastSeenAt = row[WebSessions.lastSeenAt],
        expiresAt = row[WebSessions.expiresAt],
        origin = row[WebSessions.origin],
        userAgent = row[WebSessions.userAgent]
    )

    private companion object {
        const val ORIGIN_LIMIT = 128
        const val USER_AGENT_LIMIT = 256
    }
}
