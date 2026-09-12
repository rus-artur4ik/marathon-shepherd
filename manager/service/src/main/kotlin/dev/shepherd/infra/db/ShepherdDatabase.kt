package dev.shepherd.infra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.shepherd.infra.audit.AuditLog
import dev.shepherd.infra.auth.Clients
import dev.shepherd.infra.devices.DeviceMaintenance
import dev.shepherd.infra.providers.RegisteredProviders
import dev.shepherd.infra.state.SessionLeases
import dev.shepherd.infra.state.Sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * The manager's one database, shared by sessions, clients and the audit log. SQLite by default;
 * Postgres when `MSH_DB_URL` points at one, for deployments that want backups and failover from
 * their database rather than from a file on a pod's volume.
 *
 * Either way exactly one manager may run against it — see [InstanceLock].
 *
 * Every store runs its transactions against [database] explicitly instead of Exposed's global
 * default, so two managers in one JVM (tests) never write into each other's data.
 */
class ShepherdDatabase private constructor(
    val database: Database,
    /** Where the data lives, for logs; never contains credentials. */
    val description: String,
    private val dataSource: HikariDataSource? = null
) : AutoCloseable {
    /** Postgres has server-side locks and a connection pool; SQLite has neither. */
    val isPostgres: Boolean get() = dataSource != null

    suspend fun <T> tx(block: Transaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(database) { block() }
    }

    /**
     * A connection of its own, outside the transactions the stores run. Only [InstanceLock] uses
     * it, to hold a session-level advisory lock for as long as the process lives.
     */
    internal fun borrowConnection(): Connection {
        val pool = checkNotNull(dataSource) { "Only the Postgres backend has a connection pool" }
        return pool.connection.also { connection -> connection.autoCommit = true }
    }

    override fun close() {
        dataSource?.close()
    }

    private fun createSchema() {
        transaction(database) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(Sessions, SessionLeases, Clients, AuditLog, DeviceMaintenance, RegisteredProviders)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ShepherdDatabase::class.java)
        private const val SQLITE_BUSY_TIMEOUT_MS: Int = 10_000
        private const val POSTGRES_PREFIX: String = "jdbc:postgresql:"

        /**
         * One manager writes and reads a handful of rows per session, so a small pool is
         * plenty; it also keeps a manager from eating a shared Postgres server's connections.
         */
        private const val POSTGRES_POOL_SIZE: Int = 10

        /** [databaseUrl] (`MSH_DB_URL`) chooses Postgres; without it the SQLite file at [sqlitePath] is used. */
        fun open(databaseUrl: String?, sqlitePath: String): ShepherdDatabase = when {
            databaseUrl.isNullOrBlank() -> sqlite(sqlitePath)
            databaseUrl.startsWith(POSTGRES_PREFIX) -> postgres(databaseUrl)
            else -> throw IllegalArgumentException(
                "MSH_DB_URL must be a Postgres JDBC URL starting with '$POSTGRES_PREFIX'; leave it unset to use SQLite"
            )
        }

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

        fun postgres(url: String, poolSize: Int = POSTGRES_POOL_SIZE): ShepherdDatabase {
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    driverClassName = "org.postgresql.Driver"
                    maximumPoolSize = poolSize
                    poolName = "shepherd"
                    // Exposed opens, commits and rolls back its own transactions.
                    isAutoCommit = false
                    transactionIsolation = "TRANSACTION_READ_COMMITTED"
                }
            )
            val database = Database.connect(dataSource)
            return ShepherdDatabase(database, withoutCredentials(url), dataSource).also { db ->
                db.createSchema()
                logger.info("Database ready at {}", db.description)
            }
        }

        /** A JDBC URL with everything that could be a password removed, for logs and error messages. */
        internal fun withoutCredentials(url: String): String {
            val withoutParameters: String = url.substringBefore('?').substringBefore('#')
            val authority: String = withoutParameters.substringAfter("//", "")
            if (!authority.contains('@')) {
                return withoutParameters
            }
            return withoutParameters.replace(authority.substringBefore('@') + "@", "")
        }
    }
}
