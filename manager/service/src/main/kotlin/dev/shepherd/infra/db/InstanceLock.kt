package dev.shepherd.infra.db

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.sql.Connection

/**
 * One manager per database. The queue, the leases and the session state assume a single writer,
 * so a second manager started against the same database stops with an explanation instead of
 * quietly fighting the first one.
 *
 * Postgres uses a session-level advisory lock held by one connection of its own; SQLite uses an
 * exclusive lock on a file beside the database. The operating system drops both when the process
 * ends, however it ends, so a crashed manager does not leave the database unusable.
 */
class InstanceLock internal constructor(
    /** What the lock is on, for logs. */
    val description: String,
    private val release: () -> Unit
) : AutoCloseable {
    override fun close() = release()

    companion object {
        private val logger = LoggerFactory.getLogger(InstanceLock::class.java)
        private const val LOCK_FILE: String = "manager.lock"

        /**
         * Stable and arbitrary: other users of the same Postgres database pick their own keys.
         * The digits spell the manager's port.
         */
        internal const val ADVISORY_KEY: Long = 6_037_000_000_001L

        /** @throws IllegalStateException when another manager already holds this database. */
        fun acquire(database: ShepherdDatabase, dataDir: File): InstanceLock {
            val lock: InstanceLock = if (database.isPostgres) advisoryLock(database) else fileLock(File(dataDir, LOCK_FILE))
            logger.info("Holding the single-manager lock on {}", lock.description)
            return lock
        }

        private fun advisoryLock(database: ShepherdDatabase): InstanceLock {
            val connection: Connection = database.borrowConnection()
            val taken: Boolean = try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_try_advisory_lock($ADVISORY_KEY)").use { rows ->
                        rows.next() && rows.getBoolean(1)
                    }
                }
            } catch (failure: Exception) {
                connection.close()
                throw failure
            }
            if (!taken) {
                connection.close()
                throw IllegalStateException(
                    "Another manager is already running against ${database.description}. " +
                        "One manager per database: stop the other one, or point this one at its own database."
                )
            }
            return InstanceLock(database.description) {
                runCatching { connection.createStatement().use { it.execute("SELECT pg_advisory_unlock($ADVISORY_KEY)") } }
                runCatching { connection.close() }
            }
        }

        private fun fileLock(file: File): InstanceLock {
            file.parentFile?.mkdirs()
            val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock: FileLock? = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Another manager inside this JVM (tests, or an embedded manager) holds it.
                null
            }
            if (lock == null) {
                channel.close()
                throw IllegalStateException(
                    "Another manager is already running against this data directory (${file.absolutePath} is locked). " +
                        "One manager per database: stop the other one, or start this one with its own MSH_DATA_DIR."
                )
            }
            return InstanceLock(file.absolutePath) {
                runCatching { lock.release() }
                runCatching { channel.close() }
            }
        }
    }
}
