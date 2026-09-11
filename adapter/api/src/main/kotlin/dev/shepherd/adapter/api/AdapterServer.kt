package dev.shepherd.adapter.api

import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.application.Application
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Common env vars read by every adapter.
 * Each adapter reads these, then adds its own service-specific vars.
 */
data class AdapterEnv(
    val port: Int,
    val advertisedAdbPort: Int,
    val secret: String,
    val accessMode: String,
    val accessAuthType: String,
    val accessScope: String
) {
    val authEnabled: Boolean get() = secret.isNotBlank()

    companion object {
        fun fromEnvironment(defaultPort: Int, defaultAdbPort: Int = 5037): AdapterEnv {
            return AdapterEnv(
                port = System.getenv("ADAPTER_PORT")?.toIntOrNull() ?: defaultPort,
                advertisedAdbPort = System.getenv("ADAPTER_ADB_PORT")?.toIntOrNull() ?: defaultAdbPort,
                secret = System.getenv("ADAPTER_SECRET").orEmpty(),
                accessMode = System.getenv("ADAPTER_ACCESS_MODE").orEmpty().ifBlank { ACCESS_EXPOSURE_DIRECT_TCP },
                accessAuthType = System.getenv("ADAPTER_ACCESS_AUTH_TYPE").orEmpty().ifBlank { ACCESS_AUTH_NETWORK },
                accessScope = System.getenv("ADAPTER_ACCESS_SCOPE").orEmpty().ifBlank { "private-network" }
            )
        }
    }

    fun buildDefaultAccess(adapterType: String, requestHost: String): AdapterAccess {
        val connectionId: String = "$adapterType-primary-adb"
        return AdapterAccess(
            preferredConnectionId = connectionId,
            connections = listOf(
                AdapterConnection(
                    id = connectionId,
                    protocol = ACCESS_PROTOCOL_ADB,
                    transport = ACCESS_TRANSPORT_TCP,
                    host = requestHost,
                    port = advertisedAdbPort,
                    exposure = accessMode,
                    auth = AdapterConnectionAuth(type = accessAuthType),
                    metadata = mapOf(
                        "scope" to accessScope,
                        "managedBy" to "adapter"
                    )
                )
            ),
            metadata = mapOf("adapterType" to adapterType)
        )
    }
}

/**
 * Starts a Ktor/Netty adapter server with standard plugins.
 * Pass an [AdapterHandler] — routing is wired up once via [adapterRoutes]; adapter-specific
 * endpoints go in [extraRoutes].
 */
fun startAdapterServer(handler: AdapterHandler, env: AdapterEnv, extraRoutes: Route.() -> Unit = {}) {
    val logger = LoggerFactory.getLogger("dev.shepherd.adapter.${handler.adapterType}")
    logger.info(
        "Starting ${handler.adapterType} adapter on port ${env.port}, " +
            "advertising ${env.accessMode} adb access on request-derived host:${env.advertisedAdbPort}"
    )
    if (!env.authEnabled) logger.warn("ADAPTER_SECRET is not set — running WITHOUT authentication")

    embeddedServer(Netty, port = env.port) {
        configureAdapterApplication(handler, env, AdapterMetrics(handler.adapterType), extraRoutes)
    }.start(wait = true)
}

/** Everything [startAdapterServer] installs, split out so tests can run it in `testApplication`. */
fun Application.configureAdapterApplication(
    handler: AdapterHandler,
    env: AdapterEnv,
    metrics: AdapterMetrics = AdapterMetrics(handler.adapterType),
    extraRoutes: Route.() -> Unit = {}
) {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                encodeDefaults = true
            }
        )
    }
    install(CallLogging) {
        // The manager polls /health and /status every few seconds; acquire and release
        // are what an operator reads the log for.
        filter { call -> call.request.path() !in QUIET_PATHS }
    }
    install(MicrometerMetrics) {
        registry = metrics.registry
        distributionStatisticConfig = AdapterMetrics.HTTP_SERVER_DISTRIBUTION
    }
    configureAdapterAuth(env.secret)
    routing {
        // Unauthenticated like /health so Prometheus can scrape it; keep the port private.
        get("/metrics") {
            call.respondText(metrics.registry.scrape(), PROMETHEUS_TEXT)
        }
        adapterRoutes(handler, env, metrics)
        extraRoutes()
    }
}

private val QUIET_PATHS: Set<String> = setOf("/health", "/status", "/metrics")
private val PROMETHEUS_TEXT: ContentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
