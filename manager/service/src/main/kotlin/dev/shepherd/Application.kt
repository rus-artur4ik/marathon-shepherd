package dev.shepherd

import dev.shepherd.api.configRoutes
import dev.shepherd.api.deviceRoutes
import dev.shepherd.api.docsRoutes
import dev.shepherd.api.healthRoutes
import dev.shepherd.api.metricsRoutes
import dev.shepherd.api.respondError
import dev.shepherd.api.sessionRoutes
import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.config.ConfigStore
import dev.shepherd.infra.metrics.MicrometerManagerMetrics
import dev.shepherd.infra.state.StateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

private val logger = LoggerFactory.getLogger("dev.shepherd.Application")
private const val DEFAULT_MANAGER_PORT: Int = 6037
private const val DEFAULT_STATE_STORE_NAME: String = "msh.db"
private const val CLEANUP_INTERVAL_MS: Long = 60_000L
private const val ADAPTER_CONNECT_TIMEOUT_MS: Long = 5_000L

/** Probe and scrape paths are hit every few seconds; logging each call buries real traffic. */
private val QUIET_PATHS: Set<String> = setOf("/live", "/ready", "/health", "/metrics")

/** JSON settings for every manager API response. */
internal val ApiJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

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
    val metrics = MicrometerManagerMetrics()

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Per-call deadlines live in RemoteAdapterProvider; this only stops a dead host
        // from holding a connection attempt open.
        install(HttpTimeout) {
            connectTimeoutMillis = ADAPTER_CONNECT_TIMEOUT_MS
        }
    }
    val providerRegistry = ProviderRegistry(configStore, httpClient, metrics)
    val sessionManager = SessionManager(providerRegistry, stateStore, configStore, metrics)
    val fleetMonitor = FleetMonitor(
        providerCatalog = providerRegistry,
        sessionCounts = { stateStore.countActiveSessions() },
        metrics = metrics,
        pollTimeout = { Duration.ofSeconds(providerRegistry.currentConfig().monitoring.providerPollTimeoutSeconds) }
    )
    val services = ManagerServices(
        providerRegistry = providerRegistry,
        stateStore = stateStore,
        sessionManager = sessionManager,
        fleetMonitor = fleetMonitor,
        metrics = metrics
    )

    val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    backgroundScope.launch {
        while (true) {
            delay(CLEANUP_INTERVAL_MS)
            try {
                sessionManager.cleanupExpiredSessions()
            } catch (e: Exception) {
                logger.error("Session cleanup failed", e)
            }
        }
    }
    fleetMonitor.start(backgroundScope) {
        Duration.ofSeconds(providerRegistry.currentConfig().monitoring.providerPollIntervalSeconds)
    }

    logger.info("Starting Marathon Shepherd on port $port")
    logger.info("Config: $configPath, providers: ${providerRegistry.activeProviders().size}")

    embeddedServer(Netty, port = port) {
        configureServer(services)
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

fun Application.configureServer(services: ManagerServices) {
    install(ContentNegotiation) {
        json(ApiJson)
    }

    install(CallLogging) {
        filter { call -> call.request.path() !in QUIET_PATHS }
    }

    install(MicrometerMetrics) {
        registry = services.metrics.registry
        distributionStatisticConfig = MicrometerManagerMetrics.HTTP_SERVER_DISTRIBUTION
    }

    // Error bodies are written as text rather than through content negotiation, so they
    // keep their shape on routes that negotiate a different format (MCP, event streams).
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, cause.rootMessage() ?: "Malformed request")
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, cause.message ?: "Invalid argument")
        }
        exception<IllegalStateException> { call, cause ->
            call.respondError(HttpStatusCode.ServiceUnavailable, cause.message ?: "Service unavailable")
        }
        exception<Exception> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respondError(HttpStatusCode.InternalServerError, "Internal server error")
        }
    }

    routing {
        healthRoutes(services)
        metricsRoutes(services.metrics.registry)
        docsRoutes()
        sessionRoutes(services.sessionManager)
        deviceRoutes(services.fleetMonitor, services::snapshotMaxAge)
        configRoutes(services.providerRegistry, services.sessionManager)
    }
}

/** The innermost message of a wrapped exception, e.g. the JSON parser's complaint behind a 400. */
private fun Throwable.rootMessage(): String? = generateSequence(this) { error -> error.cause }
    .mapNotNull { error -> error.message }
    .lastOrNull()
