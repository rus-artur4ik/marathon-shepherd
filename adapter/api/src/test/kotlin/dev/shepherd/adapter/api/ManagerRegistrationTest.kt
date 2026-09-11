package dev.shepherd.adapter.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.ConnectException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ManagerRegistrationTest {
    private val logs = ListAppender<ILoggingEvent>()
    private val registrationLogger = LoggerFactory.getLogger(ManagerRegistration::class.java) as Logger

    @BeforeTest
    fun captureLogs() {
        logs.start()
        registrationLogger.addAppender(logs)
    }

    @AfterTest
    fun stopCapturingLogs() {
        registrationLogger.detachAppender(logs)
    }

    @Test
    fun `registers at the manager's register endpoint with the token and this adapter's details`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val registration = registration(
            mockEngine { request ->
                requests += request.record()
                accepted(heartbeatSeconds = 10)
            },
            CONFIG.copy(managerUrl = "http://manager.test:8080/")
        )

        val attempt = registration.registerOnce()

        assertEquals(
            RegistrationAttempt.Registered(
                ProviderRegistrationResponse(name = "lab-host-adb", heartbeatIntervalSeconds = 10, ttlSeconds = 90)
            ),
            attempt
        )
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://manager.test:8080/api/v1/providers/register", request.url)
        assertEquals("Bearer reg-token", request.authorization)
        assertEquals(ContentType.Application.Json, request.contentType)
        assertEquals(
            ProviderRegistrationRequest(
                name = "lab-host-adb",
                url = "http://10.0.0.5:7037",
                accessHost = "10.0.0.5",
                secret = SECRET,
                adapterType = "adb"
            ),
            Json.decodeFromString<ProviderRegistrationRequest>(request.body)
        )
    }

    @Test
    fun `heartbeats at the manager's interval when it is shorter than the configured one`() = runTest {
        val times = mutableListOf<Long>()
        val registration = registration(
            mockEngine {
                times += currentTime
                accepted(heartbeatSeconds = 10)
            },
            CONFIG.copy(interval = 30.seconds)
        )

        backgroundScope.launch { registration.run() }
        advanceTimeBy(35.seconds)

        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), times)
        assertEquals(1, messages(Level.INFO).size, "only the first success is logged: ${messages(Level.INFO)}")
    }

    @Test
    fun `keeps the configured interval when the manager allows a longer one`() = runTest {
        val times = mutableListOf<Long>()
        val registration = registration(
            mockEngine {
                times += currentTime
                accepted(heartbeatSeconds = 120)
            },
            CONFIG.copy(interval = 30.seconds)
        )

        backgroundScope.launch { registration.run() }
        advanceTimeBy(65.seconds)

        assertEquals(listOf(0L, 30_000L, 60_000L), times)
    }

    @Test
    fun `backs off exponentially up to a minute while the manager refuses, then heartbeats again`() = runTest {
        val times = mutableListOf<Long>()
        val registration = registration(
            mockEngine {
                times += currentTime
                if (times.size <= 7) refused(HttpStatusCode.ServiceUnavailable) else accepted(heartbeatSeconds = 10)
            }
        )

        backgroundScope.launch { registration.run() }
        advanceTimeBy(195.seconds)

        // Waits of 2, 4, 8, 16, 32, 60 and 60 seconds, then the manager's 10 second heartbeat.
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 62_000L, 122_000L, 182_000L, 192_000L), times)
    }

    @Test
    fun `retries when the manager cannot be reached`() = runTest {
        val times = mutableListOf<Long>()
        val registration = registration(
            mockEngine {
                times += currentTime
                if (times.size == 1) throw ConnectException("Connection refused") else accepted(heartbeatSeconds = 30)
            }
        )

        backgroundScope.launch { registration.run() }
        advanceTimeBy(5.seconds)

        assertEquals(listOf(0L, 2_000L), times)
        assertTrue(messages(Level.WARN).single().contains("Cannot reach the manager at http://manager.test:8080"))
    }

    @Test
    fun `warns once per failure streak and never logs the token or the secret`() = runTest {
        val failures = ArrayDeque(
            listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized)
        )
        val registration = registration(
            mockEngine {
                val failure: HttpStatusCode? = failures.removeFirstOrNull()
                if (failure == null) {
                    accepted(heartbeatSeconds = 30)
                } else {
                    // A careless manager echoing credentials back must not get them into the log.
                    refused(failure, body = """{"error":"token reg-token or secret $SECRET is wrong"}""")
                }
            }
        )

        backgroundScope.launch { registration.run() }
        advanceTimeBy(40.seconds)

        val warnings: List<String> = messages(Level.WARN)
        assertEquals(2, warnings.size, warnings.joinToString("\n"))
        assertTrue(warnings[0].contains("HTTP 503"), warnings[0])
        assertTrue(warnings[1].contains("the registration token was rejected"), warnings[1])
        assertTrue(messages(Level.INFO).single().contains("after 4 failed attempt(s)"), messages(Level.INFO).toString())
        assertTrue(
            logs.list.none { event -> "reg-token" in event.formattedMessage || SECRET in event.formattedMessage },
            logs.list.joinToString("\n") { event -> event.formattedMessage }
        )
    }

    @Test
    fun `explains a name conflict`() = runTest {
        val registration = registration(mockEngine { refused(HttpStatusCode.Conflict) })

        backgroundScope.launch { registration.run() }
        advanceTimeBy(1.seconds)

        val warning: String = messages(Level.WARN).single()
        assertTrue(
            warning.contains("the name 'lab-host-adb' is taken by another provider or configured statically in the manager's msh.yaml"),
            warning
        )
    }

    @Test
    fun `keeps heartbeating at the configured interval when the manager's answer cannot be read`() = runTest {
        val times = mutableListOf<Long>()
        val registration = registration(
            mockEngine {
                times += currentTime
                respond("registered", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            },
            CONFIG.copy(interval = 30.seconds)
        )

        backgroundScope.launch { registration.run() }
        advanceTimeBy(65.seconds)

        assertEquals(listOf(0L, 30_000L, 60_000L), times)
        assertTrue(messages(Level.WARN).single().contains("could not be read"))
    }

    @Test
    fun `registration is off unless both the manager url and the token are set`() {
        val publicUrl = mapOf("ADAPTER_PUBLIC_URL" to "http://10.0.0.5:7037")

        assertNull(ManagerRegistrationConfig.fromEnvironment("adb", environment = publicUrl))
        assertNull(ManagerRegistrationConfig.fromEnvironment("adb", environment = publicUrl + ("MSH_MANAGER_URL" to "http://manager:8080")))
        assertNull(ManagerRegistrationConfig.fromEnvironment("adb", environment = publicUrl + ("MSH_REGISTRATION_TOKEN" to "reg-token")))
    }

    @Test
    fun `registration without a public url is refused with an explanation`() {
        val config = ManagerRegistrationConfig.fromEnvironment(
            "adb",
            environment = mapOf("MSH_MANAGER_URL" to "http://manager:8080", "MSH_REGISTRATION_TOKEN" to "reg-token")
        )

        assertNull(config)
        assertTrue(messages(Level.ERROR).single().contains("ADAPTER_PUBLIC_URL"))
    }

    @Test
    fun `reads the settings and derives a name the manager accepts`() {
        val config = ManagerRegistrationConfig.fromEnvironment(
            "adb",
            environment = mapOf(
                "MSH_MANAGER_URL" to "http://manager:8080/",
                "MSH_REGISTRATION_TOKEN" to "reg-token",
                "ADAPTER_PUBLIC_URL" to "http://10.0.0.5:7037/",
                "ADAPTER_ACCESS_HOST" to "lab-7.example.com"
            ),
            hostName = { "lab host#7" }
        )

        assertEquals(
            ManagerRegistrationConfig(
                managerUrl = "http://manager:8080",
                registrationToken = "reg-token",
                publicUrl = "http://10.0.0.5:7037",
                name = "lab-host-7-adb",
                accessHost = "lab-7.example.com",
                interval = 30.seconds
            ),
            config
        )
        assertFalse("reg-token" in config.toString())
    }

    @Test
    fun `honours an explicit name and heartbeat interval`() {
        val environment = mapOf(
            "MSH_MANAGER_URL" to "http://manager:8080",
            "MSH_REGISTRATION_TOKEN" to "reg-token",
            "ADAPTER_PUBLIC_URL" to "http://10.0.0.5:7037",
            "ADAPTER_NAME" to "rack-a.pixels",
            "MSH_REGISTRATION_INTERVAL_SECONDS" to "12"
        )

        val config = requireNotNull(
            ManagerRegistrationConfig.fromEnvironment("adb", environment = environment, hostName = { error("the name is configured") })
        )
        val withBadInterval = requireNotNull(
            ManagerRegistrationConfig.fromEnvironment("adb", environment = environment + ("MSH_REGISTRATION_INTERVAL_SECONDS" to "soon"))
        )

        assertEquals("rack-a.pixels", config.name)
        assertEquals(12.seconds, config.interval)
        assertEquals(30.seconds, withBadInterval.interval)
    }

    @Test
    fun `starts registering once the server is listening`() = runBlocking {
        val registeredPath = CompletableDeferred<String>()
        val httpClient = HttpClient(
            MockEngine { request ->
                registeredPath.complete(request.url.encodedPath)
                accepted(heartbeatSeconds = 30)
            }
        ) { installRegistrationDefaults() }
        val server = embeddedServer(Netty, port = 0) {
            runManagerRegistration(ManagerRegistration(CONFIG, adapterType = "adb", secret = SECRET, httpClient = httpClient))
        }

        server.start(wait = false)
        try {
            assertEquals("/api/v1/providers/register", withTimeout(10.seconds) { registeredPath.await() })
        } finally {
            server.stop(0, 1_000)
            httpClient.close()
        }
    }

    /** Serves requests on the test scheduler, so virtual time covers the whole heartbeat loop. */
    private fun TestScope.mockEngine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): MockEngine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            }
        )

    private fun registration(engine: MockEngine, config: ManagerRegistrationConfig = CONFIG): ManagerRegistration =
        ManagerRegistration(config, adapterType = "adb", secret = SECRET, httpClient = HttpClient(engine) { installRegistrationDefaults() })

    private fun messages(level: Level): List<String> =
        logs.list.filter { event -> event.level == level }.map { event -> event.formattedMessage }

    private companion object {
        const val SECRET = "adapter-secret"
        val CONFIG = ManagerRegistrationConfig(
            managerUrl = "http://manager.test:8080",
            registrationToken = "reg-token",
            publicUrl = "http://10.0.0.5:7037",
            name = "lab-host-adb",
            accessHost = "10.0.0.5",
            interval = 30.seconds
        )
    }
}

private data class RecordedRequest(
    val method: HttpMethod,
    val url: String,
    val authorization: String?,
    val contentType: ContentType?,
    val body: String
)

private suspend fun HttpRequestData.record(): RecordedRequest = RecordedRequest(
    method = method,
    url = url.toString(),
    authorization = headers[HttpHeaders.Authorization],
    contentType = body.contentType?.withoutParameters(),
    body = body.toByteArray().decodeToString()
)

/** A registration answer, with a field this adapter does not know to prove the client tolerates it. */
private fun MockRequestHandleScope.accepted(heartbeatSeconds: Long): HttpResponseData = respond(
    content = """{"name":"lab-host-adb","heartbeatIntervalSeconds":$heartbeatSeconds,"ttlSeconds":90,"addedLater":true}""",
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json")
)

private fun MockRequestHandleScope.refused(status: HttpStatusCode, body: String = """{"error":"refused"}"""): HttpResponseData =
    respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
