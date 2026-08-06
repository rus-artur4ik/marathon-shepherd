package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.adapterRoutes
import dev.shepherd.adapter.api.configureAdapterAuth
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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
        leaseManager = leaseManager
    )
    val adminService = AdbAdminService(adbService = adbService, leaseManager = leaseManager)
    startAdbAdapterServer(handler = handler, env = env, adminService = adminService)
}

internal fun startAdbAdapterServer(handler: AdbAdapterHandler, env: AdapterEnv, adminService: AdbAdminService) {
    val logger = LoggerFactory.getLogger("dev.shepherd.adapter.${handler.adapterType}")
    logger.info(
        "Starting ${handler.adapterType} adapter on port ${env.port}, " +
            "advertising ${env.accessMode} adb access on request-derived host:${env.advertisedAdbPort}"
    )
    if (!env.authEnabled) logger.warn("ADAPTER_SECRET is not set — running WITHOUT authentication")

    embeddedServer(Netty, port = env.port) {
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    encodeDefaults = true
                }
            )
        }
        install(CallLogging)
        configureAdapterAuth(env.secret)
        routing {
            adapterRoutes(handler, env)
            adbReloadRoutes(handler, env, adminService)
        }
    }.start(wait = true)
}
