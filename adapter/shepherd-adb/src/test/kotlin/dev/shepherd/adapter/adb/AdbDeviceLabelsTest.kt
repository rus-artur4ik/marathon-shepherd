package dev.shepherd.adapter.adb

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AdbDeviceLabelsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a serial's own labels override the wildcard key by key`() {
        val file = labelsFile("""{"*": {"rack": "a", "form": "phone"}, "serial-1": {"form": "tablet", "rooted": "true"}}""")

        val table = AdbDeviceLabels(file).current()

        assertEquals(mapOf("rack" to "a", "form" to "tablet", "rooted" to "true"), table.labelsFor("serial-1"))
        assertEquals(mapOf("rack" to "a", "form" to "phone"), table.labelsFor("serial-2"))
    }

    @Test
    fun `no file means no labels`() {
        assertEquals(emptyMap(), AdbDeviceLabels(file = null).current().labelsFor("serial-1"))
        assertEquals(emptyMap(), AdbDeviceLabels(File(tempDir, "missing.json")).current().labelsFor("serial-1"))
    }

    @Test
    fun `reloads the file when its modification time changes`() {
        val file = labelsFile("""{"serial-1": {"form": "phone"}}""")
        val labels = AdbDeviceLabels(file)
        assertEquals(mapOf("form" to "phone"), labels.current().labelsFor("serial-1"))

        // Same length, so only the modification time tells the versions apart.
        rewrite(file, """{"serial-1": {"form": "watch"}}""", modifiedAt = file.lastModified() + 5_000)

        assertEquals(mapOf("form" to "watch"), labels.current().labelsFor("serial-1"))
    }

    @Test
    fun `does not read an unchanged file again`() {
        val file = labelsFile("""{"serial-1": {"form": "phone"}}""")
        val labels = AdbDeviceLabels(file)
        labels.current()

        rewrite(file, """{"serial-1": {"form": "watch"}}""", modifiedAt = file.lastModified())

        assertEquals(mapOf("form" to "phone"), labels.current().labelsFor("serial-1"))
    }

    @Test
    fun `keeps the last good labels while the file does not parse`() {
        val file = labelsFile("""{"serial-1": {"form": "phone"}}""")
        val labels = AdbDeviceLabels(file)
        labels.current()

        rewrite(file, """{"serial-1": {"form": """, modifiedAt = file.lastModified() + 5_000)
        assertEquals(mapOf("form" to "phone"), labels.current().labelsFor("serial-1"))

        rewrite(file, """{"serial-1": {"form": ["tablet"]}}""", modifiedAt = file.lastModified() + 5_000)
        assertEquals(mapOf("form" to "phone"), labels.current().labelsFor("serial-1"))

        rewrite(file, """{"serial-1": {"form": "tablet"}}""", modifiedAt = file.lastModified() + 5_000)
        assertEquals(mapOf("form" to "tablet"), labels.current().labelsFor("serial-1"))
    }

    @Test
    fun `drops the labels when the file is deleted`() {
        val file = labelsFile("""{"*": {"rack": "a"}}""")
        val labels = AdbDeviceLabels(file)
        assertEquals(mapOf("rack" to "a"), labels.current().labelsFor("serial-1"))

        file.delete()

        assertEquals(emptyMap(), labels.current().labelsFor("serial-1"))
    }

    @Test
    fun `reads numbers and booleans as their text`() {
        val file = labelsFile("""{"*": {"rooted": false, "screen": 6.1}}""")

        assertEquals(mapOf("rooted" to "false", "screen" to "6.1"), AdbDeviceLabels(file).current().labelsFor("serial-1"))
    }

    private fun labelsFile(content: String): File = File(tempDir, "labels.json").apply { writeText(content) }

    private fun rewrite(file: File, content: String, modifiedAt: Long) {
        file.writeText(content)
        file.setLastModified(modifiedAt)
    }
}
