package dev.shepherd.adapter.adb

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AdbLeaseStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `persisted leases keep the session they were acquired for`() {
        val store = FileAdbLeaseStore(File(tempDir, "leases.json"))
        val leases = mapOf(
            "adb_1" to AdbLease(leaseId = "adb_1", devices = listOf(PIXEL), sessionId = "session-1"),
            "adb_2" to AdbLease(leaseId = "adb_2", devices = listOf(PIXEL.copy(serial = "serial-2", proxyPort = 7601)))
        )

        store.saveLeases(leases)

        assertEquals(leases, FileAdbLeaseStore(File(tempDir, "leases.json")).loadLeases())
    }

    @Test
    fun `lease files written before sessions were recorded still load`() {
        val stateFile = File(tempDir, "leases.json")
        stateFile.writeText(
            """
            [
              {
                "leaseId": "adb_1",
                "devices": [
                  {
                    "serial": "serial-1",
                    "proxyPort": 7600,
                    "apiLevel": "34",
                    "manufacturer": "Google",
                    "model": "Pixel",
                    "abi": "arm64-v8a"
                  }
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(mapOf("adb_1" to AdbLease(leaseId = "adb_1", devices = listOf(PIXEL))), FileAdbLeaseStore(stateFile).loadLeases())
    }
}

private val PIXEL = AdbLeasedDevice(
    serial = "serial-1",
    proxyPort = 7600,
    apiLevel = "34",
    manufacturer = "Google",
    model = "Pixel",
    abi = "arm64-v8a"
)
