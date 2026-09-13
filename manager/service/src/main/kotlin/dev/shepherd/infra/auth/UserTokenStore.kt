package dev.shepherd.infra.auth

import dev.shepherd.infra.db.ShepherdDatabase
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/** Personal API tokens: the keys people use from mshctl, scripts and agents. Stored as SHA-256 digests. */
object UserTokens : Table("user_tokens") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).references(Users.id)
    val name = varchar("name", 100)
    val prefix = varchar("prefix", 32)
    val hash = varchar("hash", 64).uniqueIndex()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

data class UserTokenRecord(
    val id: String,
    val userId: String,
    val name: String,
    val prefix: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val revokedAt: Instant?
) {
    fun isUsableAt(now: Instant): Boolean = revokedAt == null && (expiresAt == null || expiresAt.isAfter(now))
}

class UserTokenStore(private val db: ShepherdDatabase) {

    suspend fun insert(record: UserTokenRecord, hash: String) = db.tx {
        UserTokens.insert { row ->
            row[id] = record.id
            row[userId] = record.userId
            row[name] = record.name
            row[prefix] = record.prefix
            row[UserTokens.hash] = hash
            row[createdAt] = record.createdAt
            row[lastUsedAt] = record.lastUsedAt
            row[expiresAt] = record.expiresAt
            row[revokedAt] = record.revokedAt
        }
        Unit
    }

    suspend fun findByHash(hash: String): UserTokenRecord? = db.tx {
        UserTokens.selectAll().where { UserTokens.hash eq hash }.map(::toRecord).firstOrNull()
    }

    suspend fun findById(id: String): UserTokenRecord? = db.tx {
        UserTokens.selectAll().where { UserTokens.id eq id }.map(::toRecord).firstOrNull()
    }

    suspend fun listForUser(userId: String, includeRevoked: Boolean): List<UserTokenRecord> = db.tx {
        UserTokens.selectAll()
            .where { if (includeRevoked) UserTokens.userId eq userId else (UserTokens.userId eq userId) and UserTokens.revokedAt.isNull() }
            .orderBy(UserTokens.createdAt to SortOrder.ASC)
            .map(::toRecord)
    }

    suspend fun revoke(id: String, at: Instant) = db.tx {
        UserTokens.update({ (UserTokens.id eq id) and UserTokens.revokedAt.isNull() }) { row -> row[revokedAt] = at }
        Unit
    }

    suspend fun touch(id: String, at: Instant) = db.tx {
        UserTokens.update({ UserTokens.id eq id }) { row -> row[lastUsedAt] = at }
        Unit
    }

    private fun toRecord(row: ResultRow): UserTokenRecord = UserTokenRecord(
        id = row[UserTokens.id],
        userId = row[UserTokens.userId],
        name = row[UserTokens.name],
        prefix = row[UserTokens.prefix],
        createdAt = row[UserTokens.createdAt],
        lastUsedAt = row[UserTokens.lastUsedAt],
        expiresAt = row[UserTokens.expiresAt],
        revokedAt = row[UserTokens.revokedAt]
    )
}
