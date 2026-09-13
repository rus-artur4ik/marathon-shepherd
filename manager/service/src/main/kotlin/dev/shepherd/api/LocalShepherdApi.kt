package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.api.dto.toDto
import dev.shepherd.api.dto.toResponse
import dev.shepherd.domain.FleetSnapshot
import dev.shepherd.domain.SessionManager
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.devices.DeviceView
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.model.ApiSelector
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionOptions
import dev.shepherd.infra.auth.Accounts
import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.DeviceDto
import dev.shepherd.protocol.DeviceQuery
import dev.shepherd.protocol.DevicesResponse
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.ShepherdApi
import dev.shepherd.protocol.UsageDto
import dev.shepherd.protocol.WhoAmIResponse
import io.ktor.server.application.ApplicationCall

private const val OWNER_ME: String = "me"
private const val KIND_USER: String = "user"
private const val KIND_CLIENT: String = "client"

/**
 * The manager's own operations, acting as [actor]. The REST routes and the embedded MCP endpoint
 * both go through it, so a request is checked and answered the same way on every path. Failures
 * are domain exceptions, which StatusPages turns into HTTP statuses.
 */
class LocalShepherdApi(private val services: ManagerServices, private val actor: Actor) : ShepherdApi {
    private val sessionManager: SessionManager get() = services.sessionManager

    override suspend fun whoAmI(): WhoAmIResponse {
        val usage = sessionManager.usageOf(actor.id)
        return WhoAmIResponse(
            id = actor.id,
            name = actor.name,
            role = actor.role.wireName,
            quota = actor.quota.toDto(),
            usage = UsageDto(activeSessions = usage.activeSessions, devices = usage.devices),
            kind = if (actor.id.startsWith(Accounts.USER_ID_PREFIX)) KIND_USER else KIND_CLIENT
        )
    }

    override suspend fun listDevices(query: DeviceQuery): DevicesResponse {
        actor.requireRole(*READER_ROLES)
        val filter: DeviceFilter = DeviceFilter.from(query)
        // The background snapshot answers by default; `refresh` polls every adapter now.
        val maxAge = services.snapshotMaxAge()
        val fleet = services.fleetMonitor
        val snapshot: FleetSnapshot = if (query.refresh) fleet.refresh() else fleet.snapshot(maxAge)
        val statuses = snapshot.providers
        val devices: List<DeviceView> = services.deviceCatalog.list(maxAge).filter(filter::matches)
        return DevicesResponse(
            providers = statuses.map { status -> status.toDto() },
            totalAvailable = statuses.sumOf { status -> status.pool.available },
            totalBusy = statuses.sumOf { status -> status.pool.busy },
            devices = devices.map { device -> device.toDto() }
        )
    }

    override suspend fun getDevice(id: String): DeviceDto {
        actor.requireRole(*READER_ROLES)
        val device: DeviceView? = services.deviceCatalog.find(id, services.snapshotMaxAge())
        return device?.toDto() ?: throw ResourceNotFoundException("Device $id not found")
    }

    suspend fun enterMaintenance(deviceId: String, reason: String?): DeviceDto {
        actor.requireRole(Role.ADMIN)
        return services.deviceCatalog.enterMaintenance(actor, deviceId, reason, services.snapshotMaxAge()).toDto()
    }

    /** False when the device was not in maintenance. */
    suspend fun leaveMaintenance(deviceId: String): Boolean {
        actor.requireRole(Role.ADMIN)
        return services.deviceCatalog.leaveMaintenance(actor, deviceId)
    }

    override suspend fun createSession(request: CreateSessionRequest): SessionResponse {
        actor.requireRole(*HOLDER_ROLES)
        val session: Session = sessionManager.createSession(
            requestedDevices = request.resolvedMaxDevices(),
            api = request.resolvedApi(),
            ttlSeconds = request.ttlSeconds,
            deviceType = request.deviceType,
            actor = actor,
            options = SessionOptions(
                name = request.name,
                metadata = request.metadata,
                priority = request.priority,
                idleTimeoutSeconds = request.idleTimeoutSeconds,
                labels = request.labels,
                deviceIds = request.deviceIds
            )
        )
        return withQueuePosition(session)
    }

    override suspend fun getSession(id: String): SessionResponse {
        actor.requireRole(*READER_ROLES)
        return withQueuePosition(findSession(id))
    }

    override suspend fun listSessions(status: String?, owner: String?): List<SessionResponse> {
        actor.requireRole(*READER_ROLES)
        // `me` selects the caller's own sessions; any other value matches owner names.
        val ownerFilter: String? = owner?.trim()?.takeIf { value -> value.isNotEmpty() }
        val sessions: List<Session> = if (ownerFilter == OWNER_ME) {
            sessionManager.listSessions(status, ownerId = actor.id)
        } else {
            sessionManager.listSessions(status).filter { session -> ownerFilter == null || session.ownerName == ownerFilter }
        }
        return sessions.map { session -> session.toResponse() }
    }

    override suspend fun waitForSession(id: String, timeoutSeconds: Long): SessionResponse {
        actor.requireRole(*HOLDER_ROLES)
        findSession(id)
        return withQueuePosition(sessionManager.waitForSession(id, timeoutSeconds, actor))
    }

    override suspend fun heartbeat(id: String): SessionResponse {
        actor.requireRole(*HOLDER_ROLES)
        return withQueuePosition(sessionManager.heartbeat(id, actor))
    }

    override suspend fun extendSession(id: String, ttlSeconds: Long): SessionResponse {
        actor.requireRole(*HOLDER_ROLES)
        return withQueuePosition(sessionManager.extendSession(id, ttlSeconds, actor))
    }

    override suspend fun releaseSession(id: String) {
        actor.requireRole(*HOLDER_ROLES)
        if (!sessionManager.releaseSession(id, actor)) {
            throw ResourceNotFoundException("Session not found")
        }
    }

    private suspend fun findSession(id: String): Session =
        sessionManager.getSession(id) ?: throw ResourceNotFoundException("Session not found")

    private suspend fun withQueuePosition(session: Session): SessionResponse =
        session.toResponse(queuePosition = sessionManager.getQueuePosition(session.id))
}

/** The caller's view of the manager. Only valid inside `authenticate(API_AUTH)`. */
fun ApplicationCall.shepherdApi(services: ManagerServices): LocalShepherdApi = LocalShepherdApi(services, actor())

/** A [DeviceQuery] checked and normalised for matching. */
private data class DeviceFilter(
    val state: String?,
    val provider: String?,
    val deviceType: String?,
    val api: ApiSelector?,
    val labels: Map<String, String>
) {
    fun matches(device: DeviceView): Boolean = (state == null || device.state == state) &&
        (provider == null || device.provider == provider) &&
        (deviceType == null || device.deviceType == deviceType) &&
        (api == null || api.matches(device.apiLevel)) &&
        labels.all { (key, value) -> device.labels[key] == value }

    companion object {
        fun from(query: DeviceQuery): DeviceFilter = DeviceFilter(
            state = query.state?.trim()?.lowercase()?.takeIf { value -> value.isNotEmpty() },
            provider = query.provider?.trim()?.takeIf { value -> value.isNotEmpty() },
            deviceType = query.deviceType?.trim()?.lowercase()?.takeIf { value -> value.isNotEmpty() },
            api = query.api?.takeIf { value -> value.isNotBlank() }?.let(ApiSelector::parse),
            labels = query.labels
        )
    }
}
