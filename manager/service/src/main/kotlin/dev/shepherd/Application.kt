package dev.shepherd

import dev.shepherd.api.configRoutes
import dev.shepherd.api.deviceRoutes
import dev.shepherd.api.healthRoutes
import dev.shepherd.api.sessionRoutes
import dev.shepherd.domain.DeviceAllocator
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.config.ConfigStore
import dev.shepherd.infra.state.StateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val logger = LoggerFactory.getLogger("dev.shepherd.Application")
private const val DEFAULT_MANAGER_PORT: Int = 6037
private const val DEFAULT_STATE_STORE_NAME: String = "msh.db"
private const val MANAGER_VERSION: String = "0.1.0"

fun main(args: Array<String>) {
    val port: Int = readIntEnv("MSH_PORT") ?: DEFAULT_MANAGER_PORT
    val configPath: String = parseArg(args, "--config")
        ?: readStringEnv("MSH_CONFIG")
        ?: "msh.yaml"
    val dataDir: String = readStringEnv("MSH_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.msh"

    File(dataDir).mkdirs()

    val configStore = ConfigStore(configPath)
    val stateStore = StateStore(resolveStateStorePath(dataDir))

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    val providerRegistry = ProviderRegistry(configStore, httpClient)
    val deviceAllocator = DeviceAllocator(providerRegistry)
    val sessionManager = SessionManager(providerRegistry, stateStore)

    val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    cleanupScope.launch {
        while (true) {
            delay(60_000L)
            try {
                sessionManager.cleanupExpiredSessions()
            } catch (e: Exception) {
                logger.error("Session cleanup failed", e)
            }
        }
    }

    logger.info("Starting Marathon Shepherd on port $port")
    logger.info("Config: $configPath, providers: ${providerRegistry.activeProviders().size}")

    embeddedServer(Netty, port = port) {
        configureServer(sessionManager, deviceAllocator, providerRegistry)
    }.start(wait = true)
}

private fun readStringEnv(name: String): String? {
    return System.getenv(name)
        ?.takeIf { value -> value.isNotBlank() }
}

private fun readIntEnv(name: String): Int? {
    return readStringEnv(name)?.toIntOrNull()
}

/**
 * Parses `--name=value` or `--name value` from the arg list.
 * Returns null if the flag is absent.
 */
private fun parseArg(args: Array<String>, name: String): String? {
    val prefix = "$name="
    val byEquals = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    if (byEquals != null) return byEquals
    val idx = args.indexOfFirst { it == name }
    return if (idx >= 0) args.getOrNull(idx + 1)?.takeIf { !it.startsWith("--") } else null
}

private fun resolveStateStorePath(dataDir: String): String {
    return File(dataDir, DEFAULT_STATE_STORE_NAME).absolutePath
}

fun Application.configureServer(
    sessionManager: SessionManager,
    deviceAllocator: DeviceAllocator,
    providerRegistry: ProviderRegistry
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }

    install(CallLogging)

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Invalid argument")))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (cause.message ?: "Service unavailable")))
        }
        exception<Exception> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    routing {
        sessionRoutes(sessionManager)
        deviceRoutes(deviceAllocator)
        configRoutes(providerRegistry, sessionManager)
        healthRoutes(deviceAllocator, version = MANAGER_VERSION)
    }
}
