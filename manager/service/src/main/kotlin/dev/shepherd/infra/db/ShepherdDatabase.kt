package dev.shepherd.infra.db

import dev.shepherd.infra.audit.AuditLog
import dev.shepherd.infra.auth.Clients
import dev.shepherd.infra.state.SessionLeases
import dev.shepherd.infra.state.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * The manager's one database, shared by sessions, clients and the audit log.
 *
 * Every store runs its transactions against [database] explicitly instead of Exposed's global
 * default, so two managers in one JVM (tests) never write into each other's file.
 */
class ShepherdDatabase private constructor(
    val database: Database,
    /** Where the data lives, for logs; never contains credentials. */
    val description: String
) {
    suspend fun <T> tx(block: Transaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(database) { block() }
    }

    private fun createSchema() {
        transaction(database) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(Sessions, SessionLeases, Clients, AuditLog)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ShepherdDatabase::class.java)
        private const val SQLITE_BUSY_TIMEOUT_MS: Int = 10_000

        fun sqlite(path: String): ShepherdDatabase {
            val database = Database.connect(
                url = "jdbc:sqlite:$path",
                driver = "org.sqlite.JDBC",
                setupConnection = { connection ->
                    connection.createStatement().use { statement ->
                        // Sessions, heartbeats and audit entries are written from concurrent
                        // coroutines: wait for the file lock instead of failing with SQLITE_BUSY,
                        // and let readers proceed while one of them writes.
                        statement.execute("PRAGMA busy_timeout = $SQLITE_BUSY_TIMEOUT_MS")
                        statement.execute("PRAGMA journal_mode = WAL")
                    }
                }
            )
            return ShepherdDatabase(database, "sqlite:$path").also { db ->
                db.createSchema()
                logger.info("Database ready at {}", db.description)
            }
        }
    }
}
