package dev.shepherd.adapter.cuttlefish

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudOrchestratorLeaseStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `should persist and restore leases`() {
        val stateFile = File(tempDir, "leases.json")
        val leaseStore = FileCloudOrchestratorLeaseStore(stateFile)
        val leases = mapOf(
            "lease_a" to CloudOrchestratorLease(group = "group-a", count = 2),
            "lease_b" to CloudOrchestratorLease(group = "group-b", count = 1)
        )

        leaseStore.saveLeases(leases)

        val restored = leaseStore.loadLeases()

        assertEquals(leases, restored)
    }
}
