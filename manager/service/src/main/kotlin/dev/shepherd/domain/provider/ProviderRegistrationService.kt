package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.ProviderRegistrationRequest
import dev.shepherd.adapter.api.ProviderRegistrationResponse
import dev.shepherd.domain.audit.AuditActions
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.events.EventPublisher
import dev.shepherd.domain.events.EventTypes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Clock

/** Persistence for self-registrations, so they survive a manager restart. */
interface RegistrationRepository {
    suspend fun save(registration: ProviderRegistration)
    suspend fun delete(name: String)
    suspend fun all(): List<ProviderRegistration>
}

/**
 * Adapters joining the fleet on their own. An adapter with a `provider` key registers under
 * a name and keeps heartbeating the same call; the manager then polls and allocates from it
 * like a provider listed in msh.yaml.
 */
class ProviderRegistrationService(
    private val registry: ProviderRegistry,
    private val repository: RegistrationRepository,
    private val hasActiveLeases: suspend (String) -> Boolean,
    private val audit: AuditTrail = AuditTrail.NONE,
    private val events: EventPublisher = EventPublisher.NONE,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(ProviderRegistrationService::class.java)

    /** Loads registrations saved by an earlier run; each counts as fresh only within its TTL. */
    suspend fun restore(): Int {
        val saved: List<ProviderRegistration> = repository.all()
        val static: Set<String> = registry.staticProviderNames()
        saved.filterNot { registration -> registration.name in static }.forEach(registry::upsertRegistration)
        return saved.size
    }

    suspend fun register(actor: Actor, request: ProviderRegistrationRequest): ProviderRegistrationResponse {
        val name: String = request.name.trim()
        require(NAME_PATTERN.matches(name)) {
            "Provider name must be 1-100 characters of letters, digits, '.', '_' or '-', starting with a letter or digit"
        }
        val url: String = request.url.trim().trimEnd('/')
        require(isHttpUrl(url)) { "url must be an absolute http(s) URL, e.g. http://10.0.0.5:7037" }
        if (name in registry.staticProviderNames()) {
            throw ConflictException("Provider '$name' is configured in msh.yaml; register under another name")
        }
        val existing: ProviderRegistration? = registry.registration(name)
        if (existing != null && existing.clientId != actor.id && !actor.isAdmin) {
            throw ConflictException("Provider '$name' is registered by '${existing.clientName}'")
        }

        val now = clock.instant()
        val registration = ProviderRegistration(
            name = name,
            url = url,
            accessHost = request.accessHost?.trim()?.takeIf { host -> host.isNotEmpty() },
            secret = request.secret,
            adapterType = request.adapterType?.trim()?.takeIf { type -> type.isNotEmpty() },
            clientId = existing?.clientId ?: actor.id,
            clientName = existing?.clientName ?: actor.name,
            registeredAt = existing?.registeredAt ?: now,
            lastSeenAt = now
        )
        val heartbeatOnly: Boolean = existing != null && registry.isFresh(existing) && existing.sameEndpoint(registration)
        registry.upsertRegistration(registration)
        repository.save(registration)
        if (!heartbeatOnly) {
            if (registration.secret.isBlank()) {
                logger.warn("Provider '{}' registered without a secret; the manager will call it unauthenticated", name)
            }
            logger.info("Provider '{}' registered by {} at {}", name, actor.name, url)
            audit.record(actor, AuditActions.PROVIDER_REGISTER, target = name, details = mapOf("url" to url))
            events.publish(
                EventTypes.PROVIDER_REGISTERED,
                buildJsonObject {
                    put("provider", name)
                    put("url", url)
                }
            )
        }
        val config = registry.currentConfig().registration
        return ProviderRegistrationResponse(
            name = name,
            heartbeatIntervalSeconds = config.heartbeatIntervalSeconds,
            ttlSeconds = config.ttlSeconds
        )
    }

    suspend fun deregister(actor: Actor, name: String) {
        if (name in registry.staticProviderNames()) {
            throw ConflictException("Provider '$name' is configured in msh.yaml; remove it there")
        }
        registry.registration(name) ?: throw ResourceNotFoundException("No self-registered provider named '$name'")
        if (hasActiveLeases(name)) {
            throw ConflictException("Provider '$name' still holds devices for active sessions")
        }
        registry.removeRegistration(name)
        repository.delete(name)
        audit.record(actor, AuditActions.PROVIDER_DEREGISTER, target = name)
        events.publish(EventTypes.PROVIDER_DEREGISTERED, buildJsonObject { put("provider", name) })
    }

    private fun isHttpUrl(url: String): Boolean = try {
        val uri = URI(url)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private companion object {
        val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
    }
}
