package dev.shepherd.infra.db

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstanceLockTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a second manager on the same data directory is turned away`() {
        val database = ShepherdDatabase.sqlite(File(tempDir, "msh.db").absolutePath)

        val first = InstanceLock.acquire(database, tempDir)
        val refused = assertFailsWith<IllegalStateException> { InstanceLock.acquire(database, tempDir) }
        first.close()
        val afterRelease = InstanceLock.acquire(database, tempDir)
        afterRelease.close()

        assertContains(refused.message.orEmpty(), "Another manager is already running")
        assertContains(refused.message.orEmpty(), "MSH_DATA_DIR")
        assertTrue(File(tempDir, "manager.lock").exists())
    }

    @Test
    fun `managers with their own data directories do not collide`() {
        val one = File(tempDir, "one").apply { mkdirs() }
        val two = File(tempDir, "two").apply { mkdirs() }

        val first = InstanceLock.acquire(ShepherdDatabase.sqlite(File(one, "msh.db").absolutePath), one)
        val second = InstanceLock.acquire(ShepherdDatabase.sqlite(File(two, "msh.db").absolutePath), two)

        assertEquals(File(one, "manager.lock").absolutePath, first.description)
        assertEquals(File(two, "manager.lock").absolutePath, second.description)
        first.close()
        second.close()
    }
}
