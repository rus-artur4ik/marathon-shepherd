package dev.shepherd.domain.devices

import dev.shepherd.adapter.api.AdapterDevice
import dev.shepherd.adapter.api.DEVICE_STATE_OFFLINE
import dev.shepherd.domain.FleetMonitor
import dev.shepherd.domain.ProviderStatus
import dev.shepherd.domain.audit.AuditActions
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.events.EventPublisher
import dev.shepherd.domain.events.EventTypes
import dev.shepherd.domain.model.DeviceIds
import dev.shepherd.domain.model.Session
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A device in maintenance stays visible but is never allocated. */
const val DEVICE_STATE_MAINTENANCE: String = "maintenance"

data class MaintenanceInfo(
    val reason: String?,
    val setBy: String,
    val setAt: Instant
)

/** Where maintenance flags live; keyed by global device id. */
interface MaintenanceRegistry {
    suspend fun all(): Map<String, MaintenanceInfo>
    suspend fun set(deviceId: String, info: MaintenanceInfo)
    suspend fun clear(deviceId: String): Boolean
}

/** One device as the manager presents it: what the adapter reports, plus maintenance and the session holding it. */
data class DeviceView(
    /** Global id, `<provider>:<device id>`. */
    val id: String,
    val provider: String,
    val localId: String,
    val deviceType: String,
    /** available, busy, offline or maintenance. */
    val state: String,
    val apiLevel: String?,
    val manufacturer: String?,
    val model: String?,
    val abi: String?,
    val labels: Map<String, String>,
    val details: Map<String, String>,
    val leaseId: String?,
    val sessionId: String?,
    val owner: String?,
    val maintenance: MaintenanceInfo?,
    val providerHealthy: Boolean
)

/**
 * The fleet, device by device, for providers that report individual devices.
 *
 * Reads come from the fleet snapshot, so listing devices never fans out to adapters on its
 * own. Maintenance is the manager's own state: adapters do not know about it, and
 * allocation excludes those devices explicitly.
 */
class DeviceCatalog(
    private val fleetMonitor: FleetMonitor,
    private val readySessions: suspend () -> List<Session>,
    private val maintenance: MaintenanceRegistry,
    private val audit: AuditTrail = AuditTrail.NONE,
    private val events: EventPublisher = EventPublisher.NONE,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun list(maxAge: Duration, refresh: Boolean = false): List<DeviceView> {
        val snapshot = if (refresh) fleetMonitor.refresh() else fleetMonitor.snapshot(maxAge)
        val inMaintenance: Map<String, MaintenanceInfo> = maintenance.all()
        val holders: Map<String, Session> = readySessions()
            .flatMap { session -> session.devices.map { device -> device.id to session } }
            .toMap()
        return snapshot.providers.flatMap { provider ->
            provider.devices.map { device -> view(provider, device, inMaintenance, holders) }
        }
    }

    suspend fun find(id: String, maxAge: Duration): DeviceView? = list(maxAge).firstOrNull { view -> view.id == id }

    /** Global ids of devices in maintenance. */
    suspend fun maintenanceIds(): Set<String> = maintenance.all().keys

    suspend fun enterMaintenance(actor: Actor, id: String, reason: String?, maxAge: Duration): DeviceView {
        find(id, maxAge) ?: throw ResourceNotFoundException("Device $id not found")
        val trimmedReason: String? = reason?.trim()?.takeIf { text -> text.isNotEmpty() }?.take(MAX_REASON_LENGTH)
        maintenance.set(id, MaintenanceInfo(reason = trimmedReason, setBy = actor.name, setAt = clock.instant()))
        audit.record(
            actor,
            AuditActions.DEVICE_MAINTENANCE_ENABLE,
            target = id,
            details = trimmedReason?.let { text -> mapOf("reason" to text) } ?: emptyMap()
        )
        events.publish(
            EventTypes.DEVICE_MAINTENANCE,
            buildJsonObject {
                put("deviceId", id)
                put("maintenance", true)
                put("by", actor.name)
                trimmedReason?.let { text -> put("reason", text) }
            }
        )
        return find(id, maxAge) ?: throw ResourceNotFoundException("Device $id not found")
    }

    /** Takes [id] out of maintenance; true when it was in maintenance. */
    suspend fun leaveMaintenance(actor: Actor, id: String): Boolean {
        val cleared: Boolean = maintenance.clear(id)
        if (cleared) {
            audit.record(actor, AuditActions.DEVICE_MAINTENANCE_DISABLE, target = id)
            events.publish(
                EventTypes.DEVICE_MAINTENANCE,
                buildJsonObject {
                    put("deviceId", id)
                    put("maintenance", false)
                    put("by", actor.name)
                }
            )
        }
        return cleared
    }

    private fun view(
        provider: ProviderStatus,
        device: AdapterDevice,
        inMaintenance: Map<String, MaintenanceInfo>,
        holders: Map<String, Session>
    ): DeviceView {
        val id: String = DeviceIds.global(provider.name, device.id)
        val info: MaintenanceInfo? = inMaintenance[id]
        val holder: Session? = holders[id]
        // An unreachable provider's device list is stale; report its devices as offline.
        val state: String = when {
            !provider.isHealthy -> DEVICE_STATE_OFFLINE
            info != null -> DEVICE_STATE_MAINTENANCE
            else -> device.state
        }
        val details: Map<String, String> = if (provider.isHealthy) {
            device.metadata
        } else {
            device.metadata + ("reason" to "provider ${provider.name} is unreachable")
        }
        return DeviceView(
            id = id,
            provider = provider.name,
            localId = device.id,
            deviceType = device.deviceType,
            state = state,
            apiLevel = device.apiLevel,
            manufacturer = device.manufacturer,
            model = device.model,
            abi = device.abi,
            labels = device.labels,
            details = details,
            leaseId = device.leaseId,
            sessionId = holder?.id,
            owner = holder?.ownerName,
            maintenance = info,
            providerHealthy = provider.isHealthy
        )
    }

    private companion object {
        const val MAX_REASON_LENGTH: Int = 500
    }
}
