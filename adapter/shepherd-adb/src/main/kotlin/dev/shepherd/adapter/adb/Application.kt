package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.startAdapterServer
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
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
    val handler = AdbAdapterHandler(
        adbService = adbService,
        leaseManager = leaseManager,
        labels = AdbDeviceLabels.fromEnvironment()
    )
    val adminService = AdbAdminService(adbService = adbService, leaseManager = leaseManager)
    startAdbAdapterServer(handler = handler, env = env, adminService = adminService)
}

internal fun startAdbAdapterServer(handler: AdbAdapterHandler, env: AdapterEnv, adminService: AdbAdminService) {
    startAdapterServer(handler, env) {
        adbReloadRoutes(handler, env, adminService)
    }
}
