package dev.shepherd

import dev.shepherd.api.API_AUTH
import dev.shepherd.api.BEARER_CHALLENGE
import dev.shepherd.api.BrowserSessionGuard
import dev.shepherd.api.ErrorBodyWritten
import dev.shepherd.api.SecurityHeaders
import dev.shepherd.api.WEB_AUTH
import dev.shepherd.api.accountRoutes
import dev.shepherd.api.adminRoutes
import dev.shepherd.api.browserSessionRoutes
import dev.shepherd.api.configRoutes
import dev.shepherd.api.configureApiAuth
import dev.shepherd.api.deviceRoutes
import dev.shepherd.api.docsRoutes
import dev.shepherd.api.eventRoutes
import dev.shepherd.api.healthRoutes
import dev.shepherd.api.mcpRoutes
import dev.shepherd.api.metricsRoutes
import dev.shepherd.api.profileRoutes
import dev.shepherd.api.providerRoutes
import dev.shepherd.api.publicAuthRoutes
import dev.shepherd.api.respondError
import dev.shepherd.api.sessionRoutes
import dev.shepherd.api.userAdminRoutes
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
import dev.shepherd.infra.auth.Accounts
import dev.shepherd.infra.auth.ClientStore
import dev.shepherd.infra.auth.LdapSignIn
import dev.shepherd.infra.auth.LoginThrottle
import dev.shepherd.infra.auth.OidcSignIn
import dev.shepherd.infra.auth.PasswordHasher
import dev.shepherd.infra.auth.SignIn
import dev.shepherd.infra.auth.SignInFailure
import dev.shepherd.infra.auth.UserStore
import dev.shepherd.infra.auth.UserTokenStore
import dev.shepherd.infra.auth.UserWithPassword
import dev.shepherd.infra.auth.WebSessionStore
import dev.shepherd.infra.config.ConfigStore
import dev.shepherd.infra.db.InstanceLock
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
import kotlin.system.exitProcess
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

private val logger = LoggerFactory.getLogger("dev.shepherd.Application")
private const val DEFAULT_MANAGER_PORT: Int = 6037
private const val DEFAULT_STATE_STORE_NAME: String = "msh.db"
private const val CLEANUP_INTERVAL_MS: Long = 60_000L
private const val ADAPTER_CONNECT_TIMEOUT_MS: Long = 5_000L
private const val SIGN_IN_REQUEST_TIMEOUT_MS: Long = 15_000L
private const val INITIAL_ADMIN_PASSWORD_FILE: String = "initial-admin-password"
private const val CLEANUPS_PER_RETENTION_SWEEP: Int = 60

/** Exit code when another manager already holds the database. */
private const val EXIT_DATABASE_IN_USE: Int = 3

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
    // MSH_DB_URL points at Postgres; without it the state lives in a SQLite file in the data directory.
    val database = ShepherdDatabase.open(readStringEnv("MSH_DB_URL"), resolveStateStorePath(dataDir))
    val instanceLock: InstanceLock = try {
        InstanceLock.acquire(database, File(dataDir))
    } catch (inUse: IllegalStateException) {
        logger.error(inUse.message)
        exitProcess(EXIT_DATABASE_IN_USE)
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            instanceLock.close()
            database.close()
        }
    )
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
    val clientStore = ClientStore(database)
    val userStore = UserStore(database)
    val webSessionStore = WebSessionStore(database)
    val accessControl = AccessControl(
        clients = clientStore,
        audit = audit,
        quotaDefaults = { providerRegistry.currentConfig().quotas.defaults.toQuota() },
        staticAdminToken = readStringEnv("MSH_ADMIN_TOKEN"),
        otherAdmins = { userStore.countActiveAdmins() }
    )
    val authConfig = { providerRegistry.currentConfig().auth }
    val accounts = Accounts(
        users = userStore,
        tokens = UserTokenStore(database),
        webSessions = webSessionStore,
        passwords = PasswordHasher(),
        audit = audit,
        authConfig = authConfig,
        quotaDefaults = { providerRegistry.currentConfig().quotas.defaults.toQuota() },
        otherAdmins = { clientStore.countActiveAdmins() + if (accessControl.hasStaticAdmin) 1 else 0 }
    )
    // Providers answer sign-in calls quickly or not at all; do not hold a browser waiting on them.
    val signInHttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = ADAPTER_CONNECT_TIMEOUT_MS
            requestTimeoutMillis = SIGN_IN_REQUEST_TIMEOUT_MS
        }
    }
    val signIn = SignIn(
        accounts = accounts,
        webSessions = webSessionStore,
        ldap = LdapSignIn(),
        oidc = OidcSignIn(signInHttpClient, authConfig),
        throttle = LoginThrottle(
            maxFailures = { authConfig().sessions.maxFailedAttempts },
            lockout = { Duration.ofMinutes(authConfig().sessions.lockoutMinutes) }
        ),
        authConfig = authConfig,
        audit = audit
    )
    warnAboutPlaintextSignIn(authConfig())
    val initialPasswordFile = File(dataDir, INITIAL_ADMIN_PASSWORD_FILE)
    runBlocking { accounts.bootstrap(initialPasswordFile, readStringEnv("MSH_ADMIN_PASSWORD")) }
        ?.let { created -> announceInitialAdmin(created, initialPasswordFile, port) }
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
        accounts = accounts,
        signIn = signIn,
        metrics = metrics
    )

    val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    backgroundScope.launch {
        var cleanups = 0
        while (true) {
            delay(CLEANUP_INTERVAL_MS)
            try {
                sessionManager.cleanupExpiredSessions()
                signIn.purgeEndedSessions()
            } catch (e: Exception) {
                logger.error("Session cleanup failed", e)
            }
            cleanups += 1
            if (cleanups % CLEANUPS_PER_RETENTION_SWEEP == 0) {
                val config = providerRegistry.currentConfig()
                pruneAuditLog(auditStore, config.audit.retentionDays)
                pruneFinishedSessions(stateStore, config.sessions.retentionDays)
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

private suspend fun pruneFinishedSessions(stateStore: StateStore, retentionDays: Long) {
    try {
        val removed = stateStore.pruneFinishedSessions(Instant.now().minus(Duration.ofDays(retentionDays)))
        if (removed > 0) {
            logger.info("Deleted {} session(s) that ended more than {} days ago", removed, retentionDays)
        }
    } catch (e: Exception) {
        logger.warn("Session retention sweep failed: {}", e.message)
    }
}

/**
 * Shows the first admin's one-time password exactly once. It goes to stdout rather than the log so
 * log shipping does not copy it around; the file keeps it for operators who missed the output.
 */
private fun announceInitialAdmin(created: UserWithPassword, file: File, port: Int) {
    val rule = "=".repeat(78)
    println(
        """
        |$rule
        | Marathon Shepherd created the first admin account. Sign in once and choose a password:
        |
        |     username: ${created.user.username}
        |     password: ${created.temporaryPassword}
        |
        |     http://localhost:$port/   or   mshctl passwd --username ${created.user.username}
        |
        | A copy is in ${file.absolutePath} (readable by this user only); delete it
        | after signing in. Set MSH_ADMIN_PASSWORD to choose the first password yourself.
        |$rule
        """.trimMargin()
    )
    logger.warn("Created the first admin account; its one-time password was printed to stdout and saved to {}", file.absolutePath)
}

private fun warnAboutPlaintextSignIn(auth: dev.shepherd.domain.model.AuthConfig) {
    auth.ldap?.takeIf { ldap -> ldap.url.startsWith("ldap://") && !ldap.startTls }?.let {
        logger.warn("auth.ldap.url is plain ldap:// without startTls: passwords cross the network unencrypted")
    }
    if (auth.oidc.isNotEmpty() && auth.publicUrl?.startsWith("http://") == true) {
        logger.warn("auth.publicUrl is plain http: sign-in codes and session cookies travel unencrypted")
    }
}

private fun resolveStateStorePath(dataDir: String): String {
    return File(dataDir, DEFAULT_STATE_STORE_NAME).absolutePath
}

fun Application.configureServer(services: ManagerServices) {
    install(SecurityHeaders)

    install(CallLogging) {
        filter { call -> call.request.path() !in QUIET_PATHS }
    }

    install(MicrometerMetrics) {
        registry = services.metrics.registry
        distributionStatisticConfig = MicrometerManagerMetrics.HTTP_SERVER_DISTRIBUTION
    }

    configureApiAuth(services.accessControl, services.accounts, services.signIn)

    // Error bodies are written as text rather than through content negotiation, so they
    // keep their shape on routes that negotiate a different format (MCP, event streams).
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, cause.rootMessage() ?: "Malformed request")
        }
        exception<SerializationException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, cause.message ?: "Malformed JSON")
        }
        exception<SignInFailure> { call, cause ->
            val retryAfter: Long? = cause.retryAfterSeconds
            if (retryAfter != null) {
                call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                call.respondError(HttpStatusCode.TooManyRequests, cause.message ?: "Too many failed sign-ins")
            } else {
                call.respondError(HttpStatusCode.Unauthorized, cause.message ?: "Sign-in failed")
            }
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
            // A failed sign-in already explained itself.
            if (call.attributes.contains(ErrorBodyWritten)) {
                return@status
            }
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
        // Installed on the routing root rather than the application, because /mcp negotiates
        // with the MCP SDK's JSON settings and Ktor cannot mix application- and route-level installs.
        install(ContentNegotiation) {
            json(ApiJson)
        }
        healthRoutes(services)
        metricsRoutes(services.metrics.registry)
        docsRoutes()
        publicAuthRoutes(services)
        // Clients and scripts send a key; the web UI rides on its session cookie.
        authenticate(API_AUTH, WEB_AUTH) {
            install(BrowserSessionGuard)
            sessionRoutes(services)
            deviceRoutes(services)
            providerRoutes(services.providerRegistry, services.registrations, services.fleetMonitor, services::snapshotMaxAge)
            eventRoutes(services.eventBus, services.metrics)
            configRoutes(services.providerRegistry, services.sessionManager, services.audit)
            adminRoutes(services.accessControl, services.sessionManager)
            accountRoutes(services)
            browserSessionRoutes(services)
            profileRoutes(services)
            userAdminRoutes(services)
        }
        mcpRoutes(services)
    }
}

/** The innermost message of a wrapped exception, e.g. the JSON parser's complaint behind a 400. */
private fun Throwable.rootMessage(): String? = generateSequence(this) { error -> error.cause }
    .mapNotNull { error -> error.message }
    .lastOrNull()
