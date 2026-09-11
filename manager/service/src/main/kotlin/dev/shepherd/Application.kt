package dev.shepherd

import dev.shepherd.api.API_AUTH
import dev.shepherd.api.BEARER_CHALLENGE
import dev.shepherd.api.accountRoutes
import dev.shepherd.api.adminRoutes
import dev.shepherd.api.configRoutes
import dev.shepherd.api.configureApiAuth
import dev.shepherd.api.deviceRoutes
import dev.shepherd.api.docsRoutes
import dev.shepherd.api.eventRoutes
import dev.shepherd.api.healthRoutes
import dev.shepherd.api.metricsRoutes
import dev.shepherd.api.providerRoutes
import dev.shepherd.api.respondError
import dev.shepherd.api.sessionRoutes
import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.LeaseReconciler
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.devices.DeviceCatalog
import dev.shepherd.domain.errors.AccessDeniedException
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.domain.errors.QuotaExceededException
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.events.EventBus
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.provider.ProviderRegistrationService
import dev.shepherd.domain.provider.ProviderRegistry
import dev.shepherd.infra.audit.AuditStore
import dev.shepherd.infra.audit.StoreAuditTrail
import dev.shepherd.infra.auth.AccessControl
import dev.shepherd.infra.auth.ClientStore
import dev.shepherd.infra.config.ConfigStore
import dev.shepherd.infra.db.ShepherdDatabase
import dev.shepherd.infra.devices.MaintenanceStore
import dev.shepherd.infra.metrics.MicrometerManagerMetrics
import dev.shepherd.infra.providers.RegistrationStore
import dev.shepherd.infra.state.StateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.time.Instant
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

private val logger = LoggerFactory.getLogger("dev.shepherd.Application")
private const val DEFAULT_MANAGER_PORT: Int = 6037
private const val DEFAULT_STATE_STORE_NAME: String = "msh.db"
private const val CLEANUP_INTERVAL_MS: Long = 60_000L
private const val ADAPTER_CONNECT_TIMEOUT_MS: Long = 5_000L
private const val INITIAL_ADMIN_TOKEN_FILE: String = "initial-admin-token"
private const val CLEANUPS_PER_AUDIT_PRUNE: Int = 60

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
    val database = ShepherdDatabase.sqlite(resolveStateStorePath(dataDir))
    val stateStore = StateStore(database)
    val auditStore = AuditStore(database)
    val audit = StoreAuditTrail(auditStore)
    val metrics = MicrometerManagerMetrics()
    val eventBus = EventBus()
    val maintenanceStore = MaintenanceStore(database)

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
    val accessControl = AccessControl(
        clients = ClientStore(database),
        audit = audit,
        quotaDefaults = { providerRegistry.currentConfig().quotas.defaults.toQuota() },
        staticAdminToken = readStringEnv("MSH_ADMIN_TOKEN")
    )
    val initialTokenFile = File(dataDir, INITIAL_ADMIN_TOKEN_FILE)
    runBlocking { accessControl.bootstrap(initialTokenFile) }
        ?.let { key -> announceInitialAdminKey(key, initialTokenFile, port) }
    val sessionManager = SessionManager(
        providerCatalog = providerRegistry,
        stateStore = stateStore,
        configStore = configStore,
        metrics = metrics,
        audit = audit,
        events = eventBus,
        maintenanceDeviceIds = { maintenanceStore.all().keys },
        queuePolicy = { providerRegistry.currentConfig().scheduler.policy }
    )
    val fleetMonitor = FleetMonitor(
        providerCatalog = providerRegistry,
        sessionCounts = { stateStore.countActiveSessions() },
        metrics = metrics,
        pollTimeout = { Duration.ofSeconds(providerRegistry.currentConfig().monitoring.providerPollTimeoutSeconds) },
        events = eventBus
    )
    val deviceCatalog = DeviceCatalog(
        fleetMonitor = fleetMonitor,
        readySessions = { stateStore.listSessions(SessionStatus.READY) },
        maintenance = maintenanceStore,
        audit = audit,
        events = eventBus
    )
    val registrations = ProviderRegistrationService(
        registry = providerRegistry,
        repository = RegistrationStore(database),
        hasActiveLeases = { name -> stateStore.hasActiveLeasesForProvider(name) },
        audit = audit,
        events = eventBus
    )
    runBlocking { registrations.restore() }.takeIf { restored -> restored > 0 }?.let { restored ->
        logger.info("Restored {} self-registered provider(s); each becomes active again on its next heartbeat", restored)
    }
    val leaseReconciler = LeaseReconciler(
        providerCatalog = providerRegistry,
        knownLeaseIds = { provider -> stateStore.activeLeaseIds(provider) },
        metrics = metrics,
        audit = audit,
        events = eventBus
    )
    val services = ManagerServices(
        providerRegistry = providerRegistry,
        stateStore = stateStore,
        sessionManager = sessionManager,
        fleetMonitor = fleetMonitor,
        accessControl = accessControl,
        auditStore = auditStore,
        audit = audit,
        eventBus = eventBus,
        deviceCatalog = deviceCatalog,
        registrations = registrations,
        metrics = metrics
    )

    val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    backgroundScope.launch {
        var cleanups = 0
        while (true) {
            delay(CLEANUP_INTERVAL_MS)
            try {
                sessionManager.cleanupExpiredSessions()
            } catch (e: Exception) {
                logger.error("Session cleanup failed", e)
            }
            cleanups += 1
            if (cleanups % CLEANUPS_PER_AUDIT_PRUNE == 0) {
                pruneAuditLog(auditStore, providerRegistry.currentConfig().audit.retentionDays)
            }
        }
    }
    fleetMonitor.start(backgroundScope) {
        Duration.ofSeconds(providerRegistry.currentConfig().monitoring.providerPollIntervalSeconds)
    }
    leaseReconciler.start(
        scope = backgroundScope,
        enabled = { providerRegistry.currentConfig().reconciliation.enabled },
        interval = { Duration.ofSeconds(providerRegistry.currentConfig().reconciliation.intervalSeconds) }
    )

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

private suspend fun pruneAuditLog(auditStore: AuditStore, retentionDays: Long) {
    try {
        val removed = auditStore.pruneBefore(Instant.now().minus(Duration.ofDays(retentionDays)))
        if (removed > 0) {
            logger.info("Pruned {} audit entries older than {} days", removed, retentionDays)
        }
    } catch (e: Exception) {
        logger.warn("Audit log pruning failed: {}", e.message)
    }
}

/**
 * Shows a generated admin key exactly once. It goes to stdout rather than the log so log
 * shipping does not copy it around; the file keeps it for operators who missed the output.
 */
private fun announceInitialAdminKey(key: String, file: File, port: Int) {
    val rule = "=".repeat(78)
    println(
        """
        |$rule
        | Marathon Shepherd created the first admin API key. It is shown only once:
        |
        |     $key
        |
        | A copy is in ${file.absolutePath} (readable by this user only).
        | Use it to create named clients, then delete that file:
        |
        |     curl -X POST -H "Authorization: Bearer <key>" -H "Content-Type: application/json" \
        |          -d '{"name":"ci","role":"user"}' http://localhost:$port/api/v1/admin/clients
        |$rule
        """.trimMargin()
    )
    logger.warn("Generated the first admin API key; it was printed to stdout and saved to {}", file.absolutePath)
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

    configureApiAuth(services.accessControl)

    // Error bodies are written as text rather than through content negotiation, so they
    // keep their shape on routes that negotiate a different format (MCP, event streams).
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, cause.rootMessage() ?: "Malformed request")
        }
        exception<SerializationException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, cause.message ?: "Malformed JSON")
        }
        exception<ResourceNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, cause.message ?: "Not found")
        }
        exception<AccessDeniedException> { call, cause ->
            call.respondError(HttpStatusCode.Forbidden, cause.message ?: "Forbidden")
        }
        exception<ConflictException> { call, cause ->
            call.respondError(HttpStatusCode.Conflict, cause.message ?: "Conflict")
        }
        exception<QuotaExceededException> { call, cause ->
            call.respondError(HttpStatusCode.TooManyRequests, cause.message ?: "Quota exceeded")
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
        // The bearer provider answers 401 with an empty body; give API clients the reason.
        status(HttpStatusCode.Unauthorized) { call, _ ->
            // Keep the provider's own challenge; add one only when it is missing.
            if (call.response.headers[HttpHeaders.WWWAuthenticate] == null) {
                call.response.header(HttpHeaders.WWWAuthenticate, BEARER_CHALLENGE)
            }
            call.respondError(
                HttpStatusCode.Unauthorized,
                "Missing or invalid API key; send 'Authorization: Bearer <key>'"
            )
        }
    }

    routing {
        healthRoutes(services)
        metricsRoutes(services.metrics.registry)
        docsRoutes()
        authenticate(API_AUTH) {
            sessionRoutes(services.sessionManager)
            deviceRoutes(services.fleetMonitor, services.deviceCatalog, services::snapshotMaxAge)
            providerRoutes(services.providerRegistry, services.registrations, services.fleetMonitor, services::snapshotMaxAge)
            eventRoutes(services.eventBus, services.metrics)
            configRoutes(services.providerRegistry, services.sessionManager, services.audit)
            adminRoutes(services.accessControl, services.sessionManager)
            accountRoutes(services.sessionManager, services.auditStore)
        }
    }
}

/** The innermost message of a wrapped exception, e.g. the JSON parser's complaint behind a 400. */
private fun Throwable.rootMessage(): String? = generateSequence(this) { error -> error.cause }
    .mapNotNull { error -> error.message }
    .lastOrNull()
