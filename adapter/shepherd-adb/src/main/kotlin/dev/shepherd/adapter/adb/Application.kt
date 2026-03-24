package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.startAdapterServer
import java.io.File

fun main() {
    val env = AdapterEnv.fromEnvironment(defaultPort = 7037)
    val defaultLeasesPath = "${System.getProperty("user.home")}/.msh/adb-leases.json"
    val leasesPath = System.getenv("ADB_LEASES_PATH") ?: defaultLeasesPath
    val adbService = AdbService()
    val proxyPortRange = AdbProxyPortRange.fromEnvironment()
    val leaseManager = AdbLeaseManager(
        leaseStore = FileAdbLeaseStore(File(leasesPath)),
        proxyController = LeaseScopedAdbProxyController(
            upstream = AdbSocketAddress.fromEnvironment(),
            portPool = proxyPortRange?.let { range -> AdbProxyPortPool(range) }
        )
    )
    kotlinx.coroutines.runBlocking {
        leaseManager.restorePersistedLeases(adbService.listPhysicalDevices())
    }
    startAdapterServer(
        AdbAdapterHandler(
            adbService = adbService,
            leaseManager = leaseManager
        ),
        env
    )
}
