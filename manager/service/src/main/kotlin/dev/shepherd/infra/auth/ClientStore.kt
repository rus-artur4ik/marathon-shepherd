package dev.shepherd.infra.auth

import dev.shepherd.domain.auth.ClientQuota
import dev.shepherd.domain.auth.Role
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

object Clients : Table("clients") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val role = varchar("role", 16)
    val keyPrefix = varchar("key_prefix", 32)
    val keyHash = varchar("key_hash", 64).uniqueIndex()
    val description = text("description").nullable()
    val maxDevices = integer("max_devices").nullable()
    val maxSessionLifetimeSeconds = long("max_session_lifetime_seconds").nullable()
    val maxPriority = integer("max_priority").nullable()
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 128).nullable()
    val lastUsedAt = timestamp("last_used_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** A client as stored, without its key. */
data class ClientRecord(
    val id: String,
    val name: String,
    val role: Role,
    val keyPrefix: String,
    val description: String?,
    /** Limits set on this client explicitly. */
    val quota: ClientQuota,
    val createdAt: Instant,
    val createdBy: String?,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?
) {
    val isActive: Boolean get() = revokedAt == null
}

class ClientStore(private val db: ShepherdDatabase) {

    suspend fun insert(record: ClientRecord, keyHash: String) = db.tx {
        Clients.insert { row ->
            row[id] = record.id
            row[name] = record.name
            row[role] = record.role.wireName
            row[keyPrefix] = record.keyPrefix
            row[Clients.keyHash] = keyHash
            row[description] = record.description
            row[maxDevices] = record.quota.maxDevices
            row[maxSessionLifetimeSeconds] = record.quota.maxSessionLifetimeSeconds
            row[maxPriority] = record.quota.maxPriority
            row[createdAt] = record.createdAt
            row[createdBy] = record.createdBy
            row[lastUsedAt] = record.lastUsedAt
            row[revokedAt] = record.revokedAt
        }
        Unit
    }

    suspend fun findByKeyHash(keyHash: String): ClientRecord? = db.tx {
        Clients.selectAll().where { Clients.keyHash eq keyHash }.map(::toRecord).firstOrNull()
    }

    suspend fun findById(id: String): ClientRecord? = db.tx {
        Clients.selectAll().where { Clients.id eq id }.map(::toRecord).firstOrNull()
    }

    suspend fun findActiveByName(name: String): ClientRecord? = db.tx {
        Clients.selectAll()
            .where { (Clients.name eq name) and Clients.revokedAt.isNull() }
            .map(::toRecord)
            .firstOrNull()
    }

    suspend fun list(includeRevoked: Boolean): List<ClientRecord> = db.tx {
        val query = if (includeRevoked) Clients.selectAll() else Clients.selectAll().where { Clients.revokedAt.isNull() }
        query.orderBy(Clients.createdAt to SortOrder.ASC).map(::toRecord)
    }

    suspend fun update(record: ClientRecord) = db.tx {
        Clients.update({ Clients.id eq record.id }) { row ->
            row[role] = record.role.wireName
            row[description] = record.description
            row[maxDevices] = record.quota.maxDevices
            row[maxSessionLifetimeSeconds] = record.quota.maxSessionLifetimeSeconds
            row[maxPriority] = record.quota.maxPriority
        }
        Unit
    }

    suspend fun replaceKey(id: String, keyPrefix: String, keyHash: String) = db.tx {
        Clients.update({ Clients.id eq id }) { row ->
            row[Clients.keyPrefix] = keyPrefix
            row[Clients.keyHash] = keyHash
        }
        Unit
    }

    suspend fun revoke(id: String, at: Instant) = db.tx {
        Clients.update({ Clients.id eq id }) { row -> row[revokedAt] = at }
        Unit
    }

    suspend fun touch(id: String, at: Instant) = db.tx {
        Clients.update({ Clients.id eq id }) { row -> row[lastUsedAt] = at }
        Unit
    }

    suspend fun countActiveAdmins(): Long = db.tx {
        Clients.selectAll()
            .where { (Clients.role eq Role.ADMIN.wireName) and Clients.revokedAt.isNull() }
            .count()
    }

    private fun toRecord(row: ResultRow): ClientRecord = ClientRecord(
        id = row[Clients.id],
        name = row[Clients.name],
        role = Role.parse(row[Clients.role]),
        keyPrefix = row[Clients.keyPrefix],
        description = row[Clients.description],
        quota = ClientQuota(
            maxDevices = row[Clients.maxDevices],
            maxSessionLifetimeSeconds = row[Clients.maxSessionLifetimeSeconds],
            maxPriority = row[Clients.maxPriority]
        ),
        createdAt = row[Clients.createdAt],
        createdBy = row[Clients.createdBy],
        lastUsedAt = row[Clients.lastUsedAt],
        revokedAt = row[Clients.revokedAt]
    )
}
