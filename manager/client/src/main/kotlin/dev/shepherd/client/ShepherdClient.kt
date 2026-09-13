package dev.shepherd.client

import dev.shepherd.protocol.AuditPage
import dev.shepherd.protocol.AuthMethodsResponse
import dev.shepherd.protocol.ChangePasswordRequest
import dev.shepherd.protocol.ChangePasswordWithLoginRequest
import dev.shepherd.protocol.ClientDto
import dev.shepherd.protocol.ClientKeyResponse
import dev.shepherd.protocol.CreateClientRequest
import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.CreateTokenRequest
import dev.shepherd.protocol.CreateUserRequest
import dev.shepherd.protocol.CreatedUserResponse
import dev.shepherd.protocol.DeviceDto
import dev.shepherd.protocol.DeviceQuery
import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.ErrorResponse
import dev.shepherd.protocol.EventDto
import dev.shepherd.protocol.ExtendSessionRequest
import dev.shepherd.protocol.IssuedTokenResponse
import dev.shepherd.protocol.MaintenanceRequest
import dev.shepherd.protocol.PasswordResetResponse
import dev.shepherd.protocol.PersonalTokenDto
import dev.shepherd.protocol.ProviderInfoDto
import dev.shepherd.protocol.ResetPasswordRequest
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.ShepherdApi
import dev.shepherd.protocol.ShepherdApiException
import dev.shepherd.protocol.ShepherdHealthResponse
import dev.shepherd.protocol.StatusResponse
import dev.shepherd.protocol.TokenLoginRequest
import dev.shepherd.protocol.UpdateClientRequest
import dev.shepherd.protocol.UpdateUserRequest
import dev.shepherd.protocol.UserDto
import dev.shepherd.protocol.WaitSessionRequest
import dev.shepherd.protocol.WhoAmIResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Client for the Marathon Shepherd manager API.
 *
 * Every call acts as [token] and throws [ShepherdApiException] (status and the server's
 * message) on a non-2xx answer. Pass an [httpClient] to share one; otherwise this client
 * owns a CIO client with sensible timeouts and closes it in [close].
 *
 * ```
 * ShepherdClient("http://manager:6037", System.getenv("MSH_TOKEN")).use { shepherd ->
 *     shepherd.withSession(CreateSessionRequest(maxDevices = 2, api = ">=34")) { session ->
 *         session.adbServers.forEach { server -> println("adb -H ${server.host} -P ${server.port} devices") }
 *     }
 * }
 * ```
 */
class ShepherdClient(
    baseUrl: String,
    private val token: String? = null,
    httpClient: HttpClient? = null
) : ShepherdApi, Closeable {
    private val base: String = baseUrl.trimEnd('/')
    private val ownsClient: Boolean = httpClient == null
    private val http: HttpClient = httpClient ?: defaultHttpClient()

    // Per-request timeouts need the HttpTimeout plugin; a caller's client may not have it.
    private val timeoutsAvailable: Boolean = http.pluginOrNull(HttpTimeout) != null

    override suspend fun whoAmI(): WhoAmIResponse = call(HttpMethod.Get, "/api/v1/me", WhoAmIResponse.serializer())

    override suspend fun listDevices(query: DeviceQuery): DevicesResponse =
        call(HttpMethod.Get, "/api/v1/devices", DevicesResponse.serializer()) {
            if (query.refresh) url.parameters.append("refresh", "true")
            query.state?.let { value -> url.parameters.append("state", value) }
            query.provider?.let { value -> url.parameters.append("provider", value) }
            query.deviceType?.let { value -> url.parameters.append("deviceType", value) }
            query.api?.let { value -> url.parameters.append("api", value) }
            query.labels.forEach { (key, value) -> url.parameters.append("label", "$key=$value") }
        }

    override suspend fun getDevice(id: String): DeviceDto = call(HttpMethod.Get, path("devices", id), DeviceDto.serializer())

    override suspend fun createSession(request: CreateSessionRequest): SessionResponse =
        call(HttpMethod.Post, "/api/v1/sessions", SessionResponse.serializer()) { json(request, CreateSessionRequest.serializer()) }

    override suspend fun getSession(id: String): SessionResponse = call(HttpMethod.Get, path("sessions", id), SessionResponse.serializer())

    override suspend fun listSessions(status: String?, owner: String?): List<SessionResponse> =
        call(HttpMethod.Get, "/api/v1/sessions", ListSerializer(SessionResponse.serializer())) {
            status?.let { value -> url.parameters.append("status", value) }
            owner?.let { value -> url.parameters.append("owner", value) }
        }

    override suspend fun waitForSession(id: String, timeoutSeconds: Long): SessionResponse =
        call(HttpMethod.Post, path("sessions", id, "wait"), SessionResponse.serializer()) {
            json(WaitSessionRequest(timeoutSeconds), WaitSessionRequest.serializer())
            // The server holds the request for up to timeoutSeconds.
            requestTimeout((timeoutSeconds.seconds + LONG_POLL_SLACK))
        }

    override suspend fun heartbeat(id: String): SessionResponse =
        call(HttpMethod.Post, path("sessions", id, "heartbeat"), SessionResponse.serializer())

    override suspend fun extendSession(id: String, ttlSeconds: Long): SessionResponse =
        call(HttpMethod.Post, path("sessions", id, "extend"), SessionResponse.serializer()) {
            json(ExtendSessionRequest(ttlSeconds), ExtendSessionRequest.serializer())
        }

    override suspend fun releaseSession(id: String) {
        call(HttpMethod.Delete, path("sessions", id), StatusResponse.serializer())
    }

    /** Fleet health; answered with 503 (and still parsed) when no provider is healthy. */
    suspend fun health(): ShepherdHealthResponse =
        call(HttpMethod.Get, "/health", ShepherdHealthResponse.serializer(), accepted = setOf(HttpStatusCode.ServiceUnavailable))

    suspend fun enterMaintenance(deviceId: String, reason: String? = null): DeviceDto =
        call(HttpMethod.Put, path("devices", deviceId, "maintenance"), DeviceDto.serializer()) {
            json(MaintenanceRequest(reason), MaintenanceRequest.serializer())
        }

    suspend fun leaveMaintenance(deviceId: String): StatusResponse =
        call(HttpMethod.Delete, path("devices", deviceId, "maintenance"), StatusResponse.serializer())

    suspend fun listProviders(): List<ProviderInfoDto> =
        call(HttpMethod.Get, "/api/v1/providers", ListSerializer(ProviderInfoDto.serializer()))

    suspend fun deregisterProvider(name: String): StatusResponse =
        call(HttpMethod.Delete, path("providers", name), StatusResponse.serializer())

    suspend fun listClients(includeRevoked: Boolean = false): List<ClientDto> =
        call(HttpMethod.Get, "/api/v1/admin/clients", ListSerializer(ClientDto.serializer())) {
            if (includeRevoked) url.parameters.append("includeRevoked", "true")
        }

    suspend fun getClient(id: String): ClientDto = call(HttpMethod.Get, path("admin", "clients", id), ClientDto.serializer())

    suspend fun createClient(request: CreateClientRequest): ClientKeyResponse =
        call(HttpMethod.Post, "/api/v1/admin/clients", ClientKeyResponse.serializer()) { json(request, CreateClientRequest.serializer()) }

    suspend fun updateClient(id: String, request: UpdateClientRequest): ClientDto =
        call(HttpMethod.Patch, path("admin", "clients", id), ClientDto.serializer()) { json(request, UpdateClientRequest.serializer()) }

    suspend fun rotateClientKey(id: String): ClientKeyResponse =
        call(HttpMethod.Post, path("admin", "clients", id, "rotate"), ClientKeyResponse.serializer())

    suspend fun revokeClient(id: String, releaseSessions: Boolean = false): ClientDto =
        call(HttpMethod.Delete, path("admin", "clients", id), ClientDto.serializer()) {
            if (releaseSessions) url.parameters.append("releaseSessions", "true")
        }

    suspend fun audit(
        action: String? = null,
        actor: String? = null,
        target: String? = null,
        before: Long? = null,
        limit: Int? = null
    ): AuditPage = call(HttpMethod.Get, "/api/v1/audit", AuditPage.serializer()) {
        action?.let { value -> url.parameters.append("action", value) }
        actor?.let { value -> url.parameters.append("actor", value) }
        target?.let { value -> url.parameters.append("target", value) }
        before?.let { value -> url.parameters.append("before", value.toString()) }
        limit?.let { value -> url.parameters.append("limit", value.toString()) }
    }

    /** How people can sign in: local passwords, LDAP, OIDC providers. Needs no key. */
    suspend fun authMethods(): AuthMethodsResponse = call(HttpMethod.Get, "/api/v1/auth/methods", AuthMethodsResponse.serializer())

    /** Signs in with a password and returns a new personal token, shown once. Needs no key. */
    suspend fun createTokenWithPassword(username: String, password: String, name: String, expiresInDays: Int? = null): IssuedTokenResponse =
        call(HttpMethod.Post, "/api/v1/auth/tokens", IssuedTokenResponse.serializer()) {
            json(TokenLoginRequest(username, password, name, expiresInDays), TokenLoginRequest.serializer())
        }

    /** Replaces a password, a temporary one included, without a key. */
    suspend fun changePasswordWithLogin(username: String, currentPassword: String, newPassword: String) =
        send(HttpMethod.Post, "/api/v1/auth/password") {
            json(ChangePasswordWithLoginRequest(username, currentPassword, newPassword), ChangePasswordWithLoginRequest.serializer())
        }

    /** Changes the password of the person this client's token belongs to. */
    suspend fun changePassword(currentPassword: String, newPassword: String) = send(HttpMethod.Post, "/api/v1/me/password") {
        json(ChangePasswordRequest(currentPassword, newPassword), ChangePasswordRequest.serializer())
    }

    suspend fun listTokens(): List<PersonalTokenDto> = call(
        HttpMethod.Get,
        "/api/v1/me/tokens",
        ListSerializer(PersonalTokenDto.serializer())
    )

    suspend fun createToken(name: String, expiresInDays: Int? = null): IssuedTokenResponse =
        call(HttpMethod.Post, "/api/v1/me/tokens", IssuedTokenResponse.serializer()) {
            json(CreateTokenRequest(name, expiresInDays), CreateTokenRequest.serializer())
        }

    suspend fun revokeToken(id: String): PersonalTokenDto = call(HttpMethod.Delete, path("me", "tokens", id), PersonalTokenDto.serializer())

    suspend fun listUsers(includeDisabled: Boolean = false): List<UserDto> =
        call(HttpMethod.Get, "/api/v1/admin/users", ListSerializer(UserDto.serializer())) {
            if (includeDisabled) url.parameters.append("includeDisabled", "true")
        }

    suspend fun getUser(id: String): UserDto = call(HttpMethod.Get, path("admin", "users", id), UserDto.serializer())

    suspend fun createUser(request: CreateUserRequest): CreatedUserResponse =
        call(HttpMethod.Post, "/api/v1/admin/users", CreatedUserResponse.serializer()) { json(request, CreateUserRequest.serializer()) }

    suspend fun updateUser(id: String, request: UpdateUserRequest): UserDto =
        call(HttpMethod.Patch, path("admin", "users", id), UserDto.serializer()) { json(request, UpdateUserRequest.serializer()) }

    suspend fun resetUserPassword(id: String, password: String? = null): PasswordResetResponse =
        call(HttpMethod.Post, path("admin", "users", id, "password"), PasswordResetResponse.serializer()) {
            json(ResetPasswordRequest(password), ResetPasswordRequest.serializer())
        }

    suspend fun disableUser(id: String, releaseSessions: Boolean = false): UserDto =
        call(HttpMethod.Delete, path("admin", "users", id), UserDto.serializer()) {
            if (releaseSessions) url.parameters.append("releaseSessions", "true")
        }

    /** The manager's active configuration, secrets redacted; kept as JSON because its model is the manager's own. */
    suspend fun config(): JsonObject = call(HttpMethod.Get, "/api/v1/config", JsonObject.serializer())

    suspend fun reloadConfig(): JsonObject = call(HttpMethod.Post, "/api/v1/config/reload", JsonObject.serializer())

    /**
     * The manager's event stream. Reconnects after a dropped connection, resuming from the
     * last event it delivered; an authentication or permission error ends the flow instead.
     */
    fun events(
        types: List<String> = emptyList(),
        lastEventId: Long? = null,
        reconnectDelay: Duration = DEFAULT_RECONNECT_DELAY
    ): Flow<EventDto> = channelFlow {
        var resumeAfter: Long? = lastEventId
        while (isActive) {
            try {
                http.prepareGet("$base/api/v1/events") {
                    token?.let { key -> bearerAuth(key) }
                    if (types.isNotEmpty()) url.parameters.append("types", types.joinToString(","))
                    resumeAfter?.let { id -> header("Last-Event-ID", id.toString()) }
                    if (timeoutsAvailable) {
                        timeout {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        throw ShepherdApiException(response.status.value, errorMessage(response.status, response.bodyAsText()))
                    }
                    val stream = response.bodyAsChannel()
                    var data: String? = null
                    while (true) {
                        val line: String = stream.readLine() ?: break
                        when {
                            line.startsWith("data:") -> data = line.removePrefix("data:").trimStart()
                            line.isEmpty() -> {
                                data?.let { payload ->
                                    val event = JSON.decodeFromString(EventDto.serializer(), payload)
                                    resumeAfter = event.id
                                    send(event)
                                }
                                data = null
                            }
                        }
                    }
                }
            } catch (refused: ShepherdApiException) {
                if (refused.status in CLIENT_ERRORS) throw refused
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The connection dropped; reconnect below and resume from resumeAfter.
            }
            delay(reconnectDelay)
        }
    }

    /** Creates a session and waits until it is READY; see [awaitReady]. */
    suspend fun acquire(
        request: CreateSessionRequest,
        queueTimeout: Duration = DEFAULT_QUEUE_TIMEOUT,
        onProgress: (SessionResponse) -> Unit = {}
    ): SessionResponse = awaitReady(createSession(request), queueTimeout, onProgress)

    /**
     * Waits until [session] is READY, calling [onProgress] with it and with every update. If
     * the session ends up in any other state, the wait exceeds [queueTimeout] or the caller is
     * cancelled, the session is released before the exception propagates, so no queued
     * session is left behind.
     */
    suspend fun awaitReady(
        session: SessionResponse,
        queueTimeout: Duration = DEFAULT_QUEUE_TIMEOUT,
        onProgress: (SessionResponse) -> Unit = {}
    ): SessionResponse {
        var current: SessionResponse = session
        val started = TimeSource.Monotonic.markNow()
        try {
            onProgress(current)
            while (current.status == STATUS_PENDING) {
                val remaining: Duration = queueTimeout - started.elapsedNow()
                if (!remaining.isPositive()) {
                    throw QueueTimeoutException(session.id, queueTimeout)
                }
                current = waitForSession(session.id, remaining.inWholeSeconds.coerceIn(1, MAX_WAIT_SECONDS))
                onProgress(current)
            }
            if (current.status != STATUS_READY) {
                throw ShepherdApiException(HttpStatusCode.Conflict.value, "Session ${session.id} ended as ${current.status}")
            }
            return current
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { releaseSession(session.id) } }
            throw error
        }
    }

    /**
     * [acquire]s a session, runs [block] with it and always releases it afterwards. When the
     * request sets `idleTimeoutSeconds`, heartbeats keep the session alive while [block] runs.
     */
    suspend fun <T> withSession(
        request: CreateSessionRequest,
        queueTimeout: Duration = DEFAULT_QUEUE_TIMEOUT,
        block: suspend (SessionResponse) -> T
    ): T = coroutineScope {
        val session: SessionResponse = acquire(request, queueTimeout)
        val heartbeats: Job? = request.idleTimeoutSeconds?.let { idleSeconds ->
            launch {
                while (isActive) {
                    delay(heartbeatInterval(idleSeconds))
                    try {
                        heartbeat(session.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Missing one heartbeat is harmless; the next one retries.
                    }
                }
            }
        }
        try {
            block(session)
        } finally {
            withContext(NonCancellable) {
                heartbeats?.cancelAndJoin()
                runCatching { releaseSession(session.id) }
            }
        }
    }

    override fun close() {
        if (ownsClient) {
            http.close()
        }
    }

    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        serializer: KSerializer<T>,
        accepted: Set<HttpStatusCode> = emptySet(),
        configure: HttpRequestBuilder.() -> Unit = {}
    ): T {
        val response = http.request("$base$path") {
            this.method = method
            token?.let { key -> bearerAuth(key) }
            configure()
        }
        val body: String = response.bodyAsText()
        if (!response.status.isSuccess() && response.status !in accepted) {
            throw ShepherdApiException(response.status.value, errorMessage(response.status, body))
        }
        return JSON.decodeFromString(serializer, body)
    }

    /** A call whose success has no body worth reading, like `204 No Content`. */
    private suspend fun send(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit = {}) {
        val response = http.request("$base$path") {
            this.method = method
            token?.let { key -> bearerAuth(key) }
            configure()
        }
        if (!response.status.isSuccess()) {
            throw ShepherdApiException(response.status.value, errorMessage(response.status, response.bodyAsText()))
        }
    }

    private fun <T> HttpRequestBuilder.json(value: T, serializer: KSerializer<T>) {
        contentType(ContentType.Application.Json)
        setBody(JSON.encodeToString(serializer, value))
    }

    private fun HttpRequestBuilder.requestTimeout(limit: Duration) {
        if (timeoutsAvailable) {
            timeout { requestTimeoutMillis = limit.inWholeMilliseconds }
        }
    }

    private fun path(vararg segments: String): String = "/api/v1/" + segments.joinToString("/") { segment -> segment.encodeURLPathPart() }

    private fun errorMessage(status: HttpStatusCode, body: String): String {
        val serverMessage: String? = runCatching { JSON.decodeFromString(ErrorResponse.serializer(), body).error }.getOrNull()
        return serverMessage ?: body.takeIf { text -> text.isNotBlank() }?.take(ERROR_BODY_LIMIT) ?: "${status.value} ${status.description}"
    }

    companion object {
        const val STATUS_PENDING: String = "PENDING"
        const val STATUS_READY: String = "READY"
        val DEFAULT_QUEUE_TIMEOUT: Duration = 15.minutes
        private val DEFAULT_RECONNECT_DELAY: Duration = 3.seconds
        private val LONG_POLL_SLACK: Duration = 30.seconds
        private const val MAX_WAIT_SECONDS: Long = 30
        private const val ERROR_BODY_LIMIT: Int = 500
        private val CLIENT_ERRORS: IntRange = 400..499

        /** Unknown fields are ignored and unset optional fields are left out of request bodies. */
        private val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 60_000
            }
        }

        private fun heartbeatInterval(idleSeconds: Long): Duration = (idleSeconds / 3).coerceIn(5, 60).seconds
    }
}

/** [ShepherdClient.acquire] gave up waiting; the session was released. */
class QueueTimeoutException(val sessionId: String, timeout: Duration) :
    ShepherdApiException(HttpStatusCode.RequestTimeout.value, "Session $sessionId was still queued after $timeout; released it")
