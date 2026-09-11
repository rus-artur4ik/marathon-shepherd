package dev.shepherd.infra.state

import dev.shepherd.domain.model.ActiveSessionCounts
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.OwnerUsage
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.infra.db.ShepherdDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant

object Sessions : Table("sessions") {
    val id = varchar("id", 50)
    val status = varchar("status", 20)
    val requestedDevices = integer("requested_devices")
    val allocatedDevices = integer("allocated_devices")
    val api = varchar("api", 64).nullable()
    val deviceType = varchar("device_type", 32).nullable()
    val adbServersJson = text("adb_servers_json")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val lastHeartbeatAt = timestamp("last_heartbeat_at")
    val releasedAt = timestamp("released_at").nullable()
    val ownerId = varchar("owner_id", 64).nullable()
    val ownerName = varchar("owner_name", 128).nullable()

    override val primaryKey = PrimaryKey(id)
}

object SessionLeases : Table("session_leases") {
    val sessionId = varchar("session_id", 50).references(Sessions.id)
    val providerName = varchar("provider_name", 100)
    val leaseId = varchar("lease_id", 200)
    val count = integer("count")
}

data class SessionLeaseRecord(
    val sessionId: String,
    val providerName: String,
    val leaseId: String,
    val count: Int
)

class StateStore(val db: ShepherdDatabase) {
    constructor(dbPath: String) : this(ShepherdDatabase.sqlite(dbPath))

    private val logger = LoggerFactory.getLogger(StateStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun saveSession(session: Session) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            Sessions.insert {
                it[id] = session.id
                it[status] = session.status.name
                it[requestedDevices] = session.requestedDevices
                it[allocatedDevices] = session.allocatedDevices
                it[api] = session.api
                it[deviceType] = session.deviceType
                it[adbServersJson] = json.encodeToString(session.adbServers)
                it[createdAt] = session.createdAt
                it[expiresAt] = session.expiresAt
                it[lastHeartbeatAt] = session.lastHeartbeatAt
                it[releasedAt] = session.releasedAt
                it[ownerId] = session.ownerId
                it[ownerName] = session.ownerName
            }
        }
    }

    suspend fun getSession(sessionId: String): Session? = withContext(Dispatchers.IO) {
        transaction(db.database) {
            Sessions.selectAll().where { Sessions.id eq sessionId }
                .map { row -> rowToSession(row) }
                .firstOrNull()
        }
    }

    suspend fun updateSessionStatus(sessionId: String, newStatus: SessionStatus) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            Sessions.update({ Sessions.id eq sessionId }) {
                it[status] = newStatus.name
                if (
                    newStatus == SessionStatus.RELEASED ||
                    newStatus == SessionStatus.EXPIRED ||
                    newStatus == SessionStatus.FAILED
                ) {
                    it[releasedAt] = Instant.now()
                }
            }
        }
    }

    suspend fun updateSession(session: Session) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            Sessions.update({ Sessions.id eq session.id }) {
                it[status] = session.status.name
                it[requestedDevices] = session.requestedDevices
                it[allocatedDevices] = session.allocatedDevices
                it[api] = session.api
                it[deviceType] = session.deviceType
                it[adbServersJson] = json.encodeToString(session.adbServers)
                it[createdAt] = session.createdAt
                it[expiresAt] = session.expiresAt
                it[lastHeartbeatAt] = session.lastHeartbeatAt
                it[releasedAt] = session.releasedAt
            }
        }
    }

    suspend fun listSessions(statusFilter: SessionStatus? = null, ownerId: String? = null): List<Session> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            val statusCondition: Op<Boolean> = if (statusFilter != null) {
                Sessions.status eq statusFilter.name
            } else {
                (Sessions.status eq SessionStatus.READY.name) or
                    (Sessions.status eq SessionStatus.PENDING.name) or
                    (Sessions.status eq SessionStatus.FAILED.name)
            }
            val condition: Op<Boolean> = if (ownerId != null) statusCondition and (Sessions.ownerId eq ownerId) else statusCondition
            Sessions.selectAll().where { condition }
                .orderBy(Sessions.createdAt to SortOrder.ASC, Sessions.id to SortOrder.ASC)
                .map { row -> rowToSession(row) }
        }
    }

    /** Active sessions and devices of one client; queued sessions count what they asked for. */
    suspend fun usageOf(ownerId: String): OwnerUsage = withContext(Dispatchers.IO) {
        transaction(db.database) {
            val rows = Sessions.selectAll().where {
                (Sessions.ownerId eq ownerId) and
                    ((Sessions.status eq SessionStatus.PENDING.name) or (Sessions.status eq SessionStatus.READY.name))
            }.toList()
            OwnerUsage(
                activeSessions = rows.size,
                devices = rows.sumOf { row ->
                    if (row[Sessions.status] == SessionStatus.PENDING.name) {
                        row[Sessions.requestedDevices]
                    } else {
                        row[Sessions.allocatedDevices]
                    }
                }
            )
        }
    }

    suspend fun listExpiredSessions(): List<Session> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            Sessions.selectAll().where {
                (
                    (Sessions.status eq SessionStatus.READY.name) or
                        (Sessions.status eq SessionStatus.PENDING.name) or
                        (Sessions.status eq SessionStatus.FAILED.name)
                    ) and (Sessions.expiresAt less Instant.now())
            }.map { row -> rowToSession(row) }
        }
    }

    suspend fun listPendingSessions(): List<Session> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            Sessions.selectAll()
                .where { Sessions.status eq SessionStatus.PENDING.name }
                .orderBy(Sessions.createdAt to SortOrder.ASC, Sessions.id to SortOrder.ASC)
                .map { row -> rowToSession(row) }
        }
    }

    suspend fun touchSessionHeartbeat(sessionId: String, heartbeatAt: Instant) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            Sessions.update({ Sessions.id eq sessionId }) {
                it[lastHeartbeatAt] = heartbeatAt
            }
        }
    }

    suspend fun listStalePendingSessions(staleBefore: Instant): List<Session> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            Sessions.selectAll().where {
                (Sessions.status eq SessionStatus.PENDING.name) and
                    (Sessions.lastHeartbeatAt less staleBefore)
            }.map { row -> rowToSession(row) }
        }
    }

    /** Queued and ready session counts plus the devices ready sessions hold, for health and metrics. */
    suspend fun countActiveSessions(): ActiveSessionCounts = withContext(Dispatchers.IO) {
        transaction(db.database) {
            val sessionCount = Sessions.id.count()
            val allocatedSum = Sessions.allocatedDevices.sum()
            val byStatus: Map<String, Pair<Long, Int>> = Sessions
                .select(Sessions.status, sessionCount, allocatedSum)
                .where {
                    (Sessions.status eq SessionStatus.PENDING.name) or (Sessions.status eq SessionStatus.READY.name)
                }
                .groupBy(Sessions.status)
                .associate { row -> row[Sessions.status] to (row[sessionCount] to (row[allocatedSum] ?: 0)) }
            val ready = byStatus[SessionStatus.READY.name]
            ActiveSessionCounts(
                pending = byStatus[SessionStatus.PENDING.name]?.first?.toInt() ?: 0,
                ready = ready?.first?.toInt() ?: 0,
                allocatedDevices = ready?.second ?: 0
            )
        }
    }

    /** True when the database answers a trivial query; backs the readiness probe. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            transaction(db.database) { exec("SELECT 1") }
            true
        } catch (error: Exception) {
            logger.warn("Database ping failed: {}", error.message)
            false
        }
    }

    suspend fun saveSessionLease(sessionId: String, providerName: String, leaseId: String, count: Int) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            SessionLeases.insert {
                it[SessionLeases.sessionId] = sessionId
                it[SessionLeases.providerName] = providerName
                it[SessionLeases.leaseId] = leaseId
                it[SessionLeases.count] = count
            }
        }
    }

    suspend fun getSessionLeases(sessionId: String): List<SessionLeaseRecord> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            SessionLeases.selectAll().where { SessionLeases.sessionId eq sessionId }
                .map { row ->
                    SessionLeaseRecord(
                        sessionId = row[SessionLeases.sessionId],
                        providerName = row[SessionLeases.providerName],
                        leaseId = row[SessionLeases.leaseId],
                        count = row[SessionLeases.count]
                    )
                }
        }
    }

    suspend fun hasActiveLeasesForProvider(providerName: String): Boolean = withContext(Dispatchers.IO) {
        transaction(db.database) {
            SessionLeases.innerJoin(Sessions).selectAll().where {
                (SessionLeases.providerName eq providerName) and
                    (
                        (Sessions.status eq SessionStatus.READY.name) or
                            (Sessions.status eq SessionStatus.PENDING.name)
                        )
            }.count() > 0
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            SessionLeases.deleteWhere { SessionLeases.sessionId eq sessionId }
            Sessions.deleteWhere { id eq sessionId }
        }
    }

    private fun rowToSession(row: org.jetbrains.exposed.sql.ResultRow): Session {
        return Session(
            id = row[Sessions.id],
            status = SessionStatus.valueOf(row[Sessions.status]),
            requestedDevices = row[Sessions.requestedDevices],
            allocatedDevices = row[Sessions.allocatedDevices],
            api = row[Sessions.api],
            deviceType = row[Sessions.deviceType],
            adbServers = json.decodeFromString<List<AdbServer>>(row[Sessions.adbServersJson]),
            createdAt = row[Sessions.createdAt],
            expiresAt = row[Sessions.expiresAt],
            lastHeartbeatAt = row[Sessions.lastHeartbeatAt],
            releasedAt = row[Sessions.releasedAt],
            ownerId = row[Sessions.ownerId],
            ownerName = row[Sessions.ownerName]
        )
    }
}
