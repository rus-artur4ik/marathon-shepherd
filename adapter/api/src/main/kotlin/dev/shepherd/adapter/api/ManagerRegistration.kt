package dev.shepherd.adapter.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Heartbeat period used when `MSH_REGISTRATION_INTERVAL_SECONDS` is not set. */
const val DEFAULT_REGISTRATION_INTERVAL_SECONDS: Long = 30

private const val REGISTER_PATH: String = "/api/v1/providers/register"
private const val CONNECT_TIMEOUT_MILLIS: Long = 5_000
private const val REQUEST_TIMEOUT_MILLIS: Long = 15_000
private const val BODY_EXCERPT_LIMIT: Int = 200
private val INITIAL_BACKOFF: Duration = 2.seconds
private val MAX_BACKOFF: Duration = 60.seconds
private val PROVIDER_NAME_UNSAFE_CHARACTERS: Regex = Regex("[^A-Za-z0-9._-]")
private val WHITESPACE: Regex = Regex("\\s+")

/** One logger for the whole registration flow, so operators can filter on it. */
private val registrationLogger: Logger = LoggerFactory.getLogger(ManagerRegistration::class.java)

/**
 * How this adapter registers itself with the manager. [fromEnvironment] documents where each
 * value comes from.
 */
data class ManagerRegistrationConfig(
    /** Base URL of the manager, from `MSH_MANAGER_URL`. */
    val managerUrl: String,
    /** Shared token the manager requires before it accepts a registration, from `MSH_REGISTRATION_TOKEN`. */
    val registrationToken: String,
    /** URL the manager uses to call this adapter back, from `ADAPTER_PUBLIC_URL`. */
    val publicUrl: String,
    /** Provider name, unique across the manager, from `ADAPTER_NAME`. */
    val name: String,
    /** Host test runners use for adb when it differs from the host in [publicUrl], from `ADAPTER_ACCESS_HOST`. */
    val accessHost: String? = null,
    /** Heartbeat period, from `MSH_REGISTRATION_INTERVAL_SECONDS`; a shorter one asked for by the manager wins. */
    val interval: Duration = DEFAULT_REGISTRATION_INTERVAL_SECONDS.seconds
) {
    /** Spelled out so that printing the config can never leak [registrationToken]. */
    override fun toString(): String =
        "ManagerRegistrationConfig(managerUrl=$managerUrl, publicUrl=$publicUrl, name=$name, accessHost=$accessHost, interval=$interval)"

    companion object {
        /**
         * Reads the registration settings, or returns null when this adapter should not register.
         *
         * Registration is on when both `MSH_MANAGER_URL` and `MSH_REGISTRATION_TOKEN` are set. It
         * also needs `ADAPTER_PUBLIC_URL`, because the manager has to call the adapter back; when
         * that is missing the problem is logged and the adapter runs unregistered rather than
         * refusing to start, since a manager may still list it statically in msh.yaml.
         *
         * `ADAPTER_NAME` defaults to `<hostname>-<adapterType>`; either way it is reduced to the
         * characters the manager accepts in provider names.
         */
        fun fromEnvironment(
            adapterType: String,
            environment: Map<String, String> = System.getenv(),
            hostName: () -> String = { localHostName(environment) }
        ): ManagerRegistrationConfig? {
            val managerUrl: String? = environment.nonBlank("MSH_MANAGER_URL")
            val registrationToken: String? = environment.nonBlank("MSH_REGISTRATION_TOKEN")
            if (managerUrl == null || registrationToken == null) {
                if (managerUrl != null || registrationToken != null) {
                    registrationLogger.warn(
                        "Self-registration is off: it needs both MSH_MANAGER_URL and MSH_REGISTRATION_TOKEN, but only {} is set",
                        if (managerUrl != null) "MSH_MANAGER_URL" else "MSH_REGISTRATION_TOKEN"
                    )
                }
                return null
            }
            val publicUrl: String? = environment.nonBlank("ADAPTER_PUBLIC_URL")?.trimEnd('/')
            if (publicUrl == null) {
                registrationLogger.error(
                    "MSH_MANAGER_URL and MSH_REGISTRATION_TOKEN are set, but ADAPTER_PUBLIC_URL is not, so this adapter " +
                        "will NOT register with the manager at {}: the manager needs that URL to reach the adapter. " +
                        "Set ADAPTER_PUBLIC_URL to this adapter's address as the manager sees it, e.g. http://10.0.0.5:7037",
                    managerUrl
                )
                return null
            }
            val configuredName: String? = environment.nonBlank("ADAPTER_NAME")
            val name: String = sanitizeProviderName(configuredName ?: "${hostName()}-$adapterType")
            if (configuredName != null && name != configuredName) {
                registrationLogger.warn(
                    "ADAPTER_NAME '{}' has characters outside [A-Za-z0-9._-]; registering as '{}'",
                    configuredName,
                    name
                )
            }
            return ManagerRegistrationConfig(
                managerUrl = managerUrl.trimEnd('/'),
                registrationToken = registrationToken,
                publicUrl = publicUrl,
                name = name,
                accessHost = environment.nonBlank("ADAPTER_ACCESS_HOST"),
                interval = parseInterval(environment.nonBlank("MSH_REGISTRATION_INTERVAL_SECONDS")).seconds
            )
        }

        private fun parseInterval(raw: String?): Long {
            if (raw == null) {
                return DEFAULT_REGISTRATION_INTERVAL_SECONDS
            }
            val seconds: Long? = raw.toLongOrNull()?.takeIf { value -> value > 0 }
            if (seconds == null) {
                registrationLogger.warn(
                    "Ignoring MSH_REGISTRATION_INTERVAL_SECONDS={}: expected a positive number of seconds; using {}",
                    raw,
                    DEFAULT_REGISTRATION_INTERVAL_SECONDS
                )
            }
            return seconds ?: DEFAULT_REGISTRATION_INTERVAL_SECONDS
        }
    }
}

/**
 * Keeps this adapter registered with the manager, so a device host joins the fleet by starting
 * its adapter rather than by an operator editing the manager's msh.yaml.
 *
 * It registers once, then registers again as a heartbeat: the manager stops routing new sessions
 * to a provider whose heartbeats stop, so an adapter that dies drops out on its own. While the
 * manager is unreachable or refuses, attempts back off exponentially (2 s, doubling, capped at
 * 60 s), so a fleet of adapters does not hammer a manager that is restarting.
 *
 * Logging is per streak rather than per attempt: one line when registration first succeeds or
 * recovers, one warning when failures start or change cause. Neither the registration token nor
 * the adapter secret is ever logged, not even inside an error body the manager echoes back.
 *
 * The [HttpClient] is passed in so tests can drive the loop with Ktor's MockEngine; production
 * code passes [defaultHttpClient].
 */
class ManagerRegistration(
    private val config: ManagerRegistrationConfig,
    adapterType: String,
    secret: String,
    private val httpClient: HttpClient
) {
    private val registerUrl: String = config.managerUrl.trimEnd('/') + REGISTER_PATH
    private val request = ProviderRegistrationRequest(
        name = config.name,
        url = config.publicUrl,
        accessHost = config.accessHost,
        secret = secret,
        adapterType = adapterType
    )
    private val redactedValues: List<String> = listOf(config.registrationToken, secret).filter { value -> value.isNotBlank() }

    /** Registers, then keeps heartbeating until the calling coroutine is cancelled. */
    suspend fun run(): Nothing {
        var registeredOnce = false
        var failedAttempts = 0
        var failureCause: String? = null
        var backoff: Duration = INITIAL_BACKOFF
        var warnedAboutUnreadableAnswer = false
        while (true) {
            val wait: Duration = when (val attempt: RegistrationAttempt = registerOnce()) {
                is RegistrationAttempt.Registered -> {
                    val heartbeat: Duration = heartbeatInterval(attempt.response)
                    if (!registeredOnce || failedAttempts > 0) {
                        registrationLogger.info(
                            "Registered with the manager at {} as '{}' ({}){}; heartbeat every {}",
                            config.managerUrl,
                            config.name,
                            config.publicUrl,
                            if (failedAttempts > 0) " after $failedAttempts failed attempt(s)" else "",
                            heartbeat
                        )
                    }
                    if (attempt.response == null && !warnedAboutUnreadableAnswer) {
                        registrationLogger.warn(
                            "The manager at {} accepted the registration, but its answer could not be read; heartbeat every {}",
                            config.managerUrl,
                            heartbeat
                        )
                        warnedAboutUnreadableAnswer = true
                    }
                    registeredOnce = true
                    failedAttempts = 0
                    failureCause = null
                    backoff = INITIAL_BACKOFF
                    heartbeat
                }

                is RegistrationAttempt.Failed -> {
                    failedAttempts += 1
                    if (attempt.cause != failureCause) {
                        registrationLogger.warn(describeFailure(attempt, retryIn = backoff))
                    } else {
                        registrationLogger.debug(
                            "Registration attempt {} failed again ({}); retrying in {}",
                            failedAttempts,
                            attempt.cause,
                            backoff
                        )
                    }
                    failureCause = attempt.cause
                    backoff.also { backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF) }
                }
            }
            delay(wait)
        }
    }

    /** One registration attempt. Never throws, except to propagate cancellation. */
    internal suspend fun registerOnce(): RegistrationAttempt {
        val response: HttpResponse = try {
            httpClient.post(registerUrl) {
                expectSuccess = false
                bearerAuth(config.registrationToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (error: Exception) {
            // Rethrow only if we are the ones being cancelled; a client-side cancellation is just a failure.
            currentCoroutineContext().ensureActive()
            val cause: String = error::class.simpleName ?: "Exception"
            return RegistrationAttempt.Failed(status = null, cause = cause, detail = redact("$cause: ${error.message}"))
        }
        if (!response.status.isSuccess()) {
            val body: String = try {
                response.bodyAsText()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                ""
            }
            return RegistrationAttempt.Failed(
                status = response.status,
                cause = "HTTP ${response.status.value}",
                detail = excerpt(body)
            )
        }
        val answer: ProviderRegistrationResponse? = try {
            response.body<ProviderRegistrationResponse>()
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
        return RegistrationAttempt.Registered(answer)
    }

    /** The configured interval, or the manager's when it asks for more frequent heartbeats. */
    private fun heartbeatInterval(answer: ProviderRegistrationResponse?): Duration {
        val requested: Duration? = answer?.heartbeatIntervalSeconds?.takeIf { seconds -> seconds > 0 }?.seconds
        return if (requested != null && requested < config.interval) requested else config.interval
    }

    private fun describeFailure(attempt: RegistrationAttempt.Failed, retryIn: Duration): String {
        val status: HttpStatusCode = attempt.status
            ?: return "Cannot reach the manager at ${config.managerUrl} to register as '${config.name}': " +
                "${attempt.detail}. Retrying in $retryIn, backing off up to $MAX_BACKOFF"
        val reason: String = when (status.value) {
            401, 403 -> "the registration token was rejected; check MSH_REGISTRATION_TOKEN"
            409 ->
                "the name '${config.name}' is taken by another provider or configured statically in the manager's " +
                    "msh.yaml; set a different ADAPTER_NAME or remove the static entry"

            else -> "the manager refused the registration"
        }
        return "Registration with the manager at ${config.managerUrl} as '${config.name}' failed with HTTP ${status.value}: " +
            "$reason. Response: ${attempt.detail}. Retrying in $retryIn, backing off up to $MAX_BACKOFF"
    }

    /** A short, single-line, redacted excerpt of a response body for the log. */
    private fun excerpt(body: String): String {
        // Redact before truncating, so a secret cut in half by the limit cannot slip through.
        val singleLine: String = redact(body).replace(WHITESPACE, " ").trim()
        return when {
            singleLine.isEmpty() -> "(empty body)"
            singleLine.length <= BODY_EXCERPT_LIMIT -> singleLine
            else -> singleLine.take(BODY_EXCERPT_LIMIT) + "..."
        }
    }

    private fun redact(text: String): String = redactedValues.fold(text) { redacted, value -> redacted.replace(value, "***") }

    companion object {
        /** CIO with JSON both ways and timeouts, so a manager that hangs cannot stall the heartbeat loop. */
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) { installRegistrationDefaults() }
    }
}

/** Outcome of one registration attempt. */
internal sealed interface RegistrationAttempt {
    /** [response] is null when the manager accepted the registration but its answer could not be read. */
    data class Registered(val response: ProviderRegistrationResponse?) : RegistrationAttempt

    /** [status] is null when the manager could not be reached; a change of [cause] starts a new failure streak. */
    data class Failed(val status: HttpStatusCode?, val cause: String, val detail: String) : RegistrationAttempt
}

/** Client setup shared by [ManagerRegistration.defaultHttpClient] and tests that swap in a mock engine. */
internal fun HttpClientConfig<*>.installRegistrationDefaults() {
    // Lenient, so a newer manager can add fields to its answer without breaking older adapters.
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
    }
}

/** Replaces everything outside `[A-Za-z0-9._-]`, the characters the manager accepts in provider names. */
internal fun sanitizeProviderName(name: String): String = name.replace(PROVIDER_NAME_UNSAFE_CHARACTERS, "-")

/** `HOSTNAME` first, as containers set it, because a lookup can stall on a host without working DNS. */
private fun localHostName(environment: Map<String, String>): String = environment.nonBlank("HOSTNAME")
    ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { name -> name.isNotBlank() }
    ?: "adapter"

private fun Map<String, String>.nonBlank(key: String): String? = this[key]?.trim()?.takeIf { value -> value.isNotEmpty() }
