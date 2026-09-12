package dev.shepherd.infra.db

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ShepherdDatabaseTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `without MSH_DB_URL the state lives in the sqlite file`() {
        val path = File(tempDir, "msh.db").absolutePath

        val database = ShepherdDatabase.open(databaseUrl = null, sqlitePath = path)

        assertEquals("sqlite:$path", database.description)
        assertFalse(database.isPostgres)
        database.close()
    }

    @Test
    fun `a database URL that is not postgres is refused with what to do instead`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            ShepherdDatabase.open("jdbc:mysql://db/shepherd", File(tempDir, "msh.db").absolutePath)
        }

        assertContains(refused.message.orEmpty(), "jdbc:postgresql:")
        assertContains(refused.message.orEmpty(), "SQLite")
    }

    @Test
    fun `the description of a postgres URL keeps no credentials`() {
        assertEquals(
            "jdbc:postgresql://db.internal:5432/shepherd",
            ShepherdDatabase.withoutCredentials("jdbc:postgresql://db.internal:5432/shepherd?user=msh&password=hunter2")
        )
        assertEquals(
            "jdbc:postgresql://db.internal:5432/shepherd",
            ShepherdDatabase.withoutCredentials("jdbc:postgresql://msh:hunter2@db.internal:5432/shepherd")
        )
    }
}
