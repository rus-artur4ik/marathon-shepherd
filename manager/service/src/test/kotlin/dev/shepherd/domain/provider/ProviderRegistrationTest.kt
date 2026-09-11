package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.ProviderRegistrationRequest
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.infra.config.ConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRegistrationTest {

    @TempDir
    lateinit var tempDir: File

    private val clock = SettableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val agent = Actor(id = "cli_agent", name = "rack-7-agent", role = Role.PROVIDER)
    private val otherAgent = Actor(id = "cli_other", name = "other-agent", role = Role.PROVIDER)
    private val request = ProviderRegistrationRequest(name = "rack-7", url = "http://10.0.0.7:7037", secret = "s3cret")

    @Test
    fun `a registered adapter is an active provider until its heartbeat lapses`() = runTest {
        val registry = registry()
        val repository = InMemoryRegistrations()
        val service = ProviderRegistrationService(registry, repository, hasActiveLeases = { false }, clock = clock)

        val response = service.register(agent, request)
        val activeAtFirst = registry.activeProviders().any { it.name == "rack-7" }
        clock.now = clock.now.plusSeconds(91)
        val activeAfterLapse = registry.activeProviders().any { it.name == "rack-7" }
        service.register(agent, request)
        val activeAfterHeartbeat = registry.activeProviders().any { it.name == "rack-7" }

        assertEquals(30, response.heartbeatIntervalSeconds)
        assertEquals(90, response.ttlSeconds)
        assertTrue(activeAtFirst)
        assertFalse(activeAfterLapse)
        assertNotNull(registry.resolveProvider("rack-7"), "a lapsed provider stays resolvable so its leases can be released")
        assertTrue(activeAfterHeartbeat)
        assertEquals(setOf("rack-7"), repository.saved.keys)
    }

    @Test
    fun `a name belongs to the client that registered it first`() = runTest {
        val service = ProviderRegistrationService(registry(), InMemoryRegistrations(), hasActiveLeases = { false }, clock = clock)
        service.register(agent, request)

        assertFailsWith<ConflictException> { service.register(otherAgent, request) }
        service.register(Actor.SYSTEM, request.copy(url = "http://10.0.0.8:7037"))
    }

    @Test
    fun `names from msh yaml and malformed registrations are refused`() = runTest {
        val service = ProviderRegistrationService(registry(), InMemoryRegistrations(), hasActiveLeases = { false }, clock = clock)

        assertFailsWith<ConflictException> { service.register(agent, request.copy(name = "rack-1")) }
        assertFailsWith<IllegalArgumentException> { service.register(agent, request.copy(name = "not a name")) }
        assertFailsWith<IllegalArgumentException> { service.register(agent, request.copy(url = "ftp://10.0.0.7")) }
        assertFailsWith<IllegalArgumentException> { service.register(agent, request.copy(url = "rack-7:7037")) }
    }

    @Test
    fun `deregistration waits until the provider holds no devices`() = runTest {
        var busy = true
        val registry = registry()
        val repository = InMemoryRegistrations()
        val service = ProviderRegistrationService(registry, repository, hasActiveLeases = { busy }, clock = clock)
        service.register(agent, request)

        assertFailsWith<ConflictException> { service.deregister(Actor.SYSTEM, "rack-7") }
        busy = false
        service.deregister(Actor.SYSTEM, "rack-7")

        assertNull(registry.registration("rack-7"))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `saved registrations come back after a restart`() = runTest {
        val repository = InMemoryRegistrations()
        ProviderRegistrationService(registry(), repository, hasActiveLeases = { false }, clock = clock).register(agent, request)
        val restartedRegistry = registry()

        val restored = ProviderRegistrationService(restartedRegistry, repository, hasActiveLeases = { false }, clock = clock).restore()

        assertEquals(1, restored)
        assertTrue(restartedRegistry.activeProviders().any { it.name == "rack-7" })
    }

    private fun registry(): ProviderRegistry {
        val config = File(tempDir, "msh.yaml")
        config.writeText(
            """
            providers:
              - name: "rack-1"
                url: "http://127.0.0.1:7037"
            """.trimIndent()
        )
        return ProviderRegistry(ConfigStore(config.absolutePath), HttpClient(CIO), clock = clock) { providerConfig, _ ->
            FakeDeviceProvider(name = providerConfig.name)
        }
    }
}

private class InMemoryRegistrations : RegistrationRepository {
    val saved = linkedMapOf<String, ProviderRegistration>()

    override suspend fun save(registration: ProviderRegistration) {
        saved[registration.name] = registration
    }

    override suspend fun delete(name: String) {
        saved.remove(name)
    }

    override suspend fun all(): List<ProviderRegistration> = saved.values.toList()
}

internal class SettableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}
