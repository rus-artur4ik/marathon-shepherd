package dev.shepherd.domain

import dev.shepherd.adapter.api.AdapterDevice
import dev.shepherd.adapter.api.DEVICE_TYPE_EMULATOR
import dev.shepherd.adapter.api.DEVICE_TYPE_PHYSICAL
import dev.shepherd.adapter.api.FEATURE_LEASE_RENEW
import dev.shepherd.domain.allocation.DeviceRequest
import dev.shepherd.domain.allocation.NoMatchingDevicesReport
import dev.shepherd.domain.allocation.ProviderMatcher
import dev.shepherd.domain.audit.AuditActions
import dev.shepherd.domain.audit.AuditOutcome
import dev.shepherd.domain.audit.AuditTrail
import dev.shepherd.domain.auth.Actor
import dev.shepherd.domain.errors.AccessDeniedException
import dev.shepherd.domain.errors.ConflictException
import dev.shepherd.domain.errors.QuotaExceededException
import dev.shepherd.domain.errors.ResourceNotFoundException
import dev.shepherd.domain.events.EventPublisher
import dev.shepherd.domain.events.EventTypes
import dev.shepherd.domain.metrics.ManagerMetrics
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.ApiSelector
import dev.shepherd.domain.model.DeviceIds
import dev.shepherd.domain.model.OwnerUsage
import dev.shepherd.domain.model.QueuePolicy
import dev.shepherd.domain.model.Session
import dev.shepherd.domain.model.SessionDevice
import dev.shepherd.domain.model.SessionOptions
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.model.SessionStatus.FAILED
import dev.shepherd.domain.provider.AcquireResult
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.DeviceSelection
import dev.shepherd.domain.provider.ProviderCatalog
import dev.shepherd.infra.config.ConfigStore
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private const val RELEASE_TIMEOUT_MS = 10_000L
private const val WAIT_POLL_INTERVAL_MS = 1_000L
private const val DEFAULT_WAIT_TIMEOUT_SECONDS = 20L
private const val MAX_WAIT_TIMEOUT_SECONDS = 30L
private const val STALE_PENDING_HEARTBEAT_SECONDS = 90L
private val SUPPORTED_DEVICE_TYPES: Set<String> = linkedSetOf(DEVICE_TYPE_PHYSICAL, DEVICE_TYPE_EMULATOR)
private const val SUPPORTED_DEVICE_TYPES_TEXT = "physical, emulator"
private const val REJECTED_NO_MATCHING_DEVICES = "no_matching_devices"
private const val REJECTED_QUOTA = "quota_exceeded"
private val FINISHED_STATUSES: Set<SessionStatus> = setOf(SessionStatus.RELEASED, SessionStatus.EXPIRED)
private val ACTIVE_STATUSES: Set<SessionStatus> = setOf(SessionStatus.PENDING, SessionStatus.READY)
private const val MIN_IDLE_TIMEOUT_SECONDS = 30L
private const val MAX_NAME_LENGTH = 200
private const val MAX_METADATA_ENTRIES = 32
private const val MAX_METADATA_KEY_LENGTH = 64
private const val MAX_METADATA_VALUE_LENGTH = 512
private const val MAX_LABELS = 16
private const val MAX_DEVICE_IDS = 64

/** Highest queue priority a session may ask for. */
const val MAX_SESSION_PRIORITY: Int = 1_000

class SessionManager(
    private val providerCatalog: ProviderCatalog,
    private val stateStore: StateStore,
    @Suppress("unused")
    private val configStore: ConfigStore? = null,
    private val metrics: ManagerMetrics = ManagerMetrics.NONE,
    private val audit: AuditTrail = AuditTrail.NONE,
    private val events: EventPublisher = EventPublisher.NONE,
    /** Global ids of devices in maintenance; they are never handed out. */
    private val maintenanceDeviceIds: suspend () -> Set<String> = { emptySet() },
    private val queuePolicy: () -> QueuePolicy = { QueuePolicy.FIFO },
) {
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    /** Serializes quota check and insert per client, so parallel requests cannot overshoot a device cap. */
    private val quotaLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun createSession(
        requestedDevices: Int,
        api: String?,
        ttlSeconds: Long,
        deviceType: String? = null,
        actor: Actor = Actor.SYSTEM,
        options: SessionOptions = SessionOptions()
    ): Session {
        require(requestedDevices > 0) { "Requested devices must be greater than zero" }
        require(ttlSeconds > 0) { "Session TTL must be greater than zero" }
        validateOptions(options, actor)

        val normalizedDeviceType: String? = normalizeDeviceType(deviceType)
        val apiSelector: ApiSelector = ApiSelector.parse(api)
        val request = DeviceRequest(normalizedDeviceType, apiSelector, options.labels, options.deviceIds.toSet())
        cleanupStalePendingSessions()

        val providers: List<DeviceProvider> = refreshMatchingProviders(normalizedDeviceType)
        rejectUnknownDeviceIds(options.deviceIds)
        if (!ProviderMatcher.hasRegisteredMatchingDevices(providers, request, maintenanceDeviceIds())) {
            metrics.sessionRejected(REJECTED_NO_MATCHING_DEVICES)
            audit.record(
                actor,
                AuditActions.SESSION_CREATE,
                outcome = AuditOutcome.FAILED,
                details = mapOf("reason" to REJECTED_NO_MATCHING_DEVICES)
            )
            throw IllegalStateException(NoMatchingDevicesReport.render(providerCatalog.activeProviders(), request))
        }

        // The lifetime cap bounds the whole session, so a long TTL cannot outlive it.
        val effectiveTtlSeconds: Long = actor.quota.maxSessionLifetimeSeconds
            ?.let { cap -> minOf(ttlSeconds, cap) }
            ?: ttlSeconds

        val session: Session = withQuotaLock(actor) {
            val grantedDevices: Int = grantDevices(actor, requestedDevices)
            val now = Instant.now()
            Session(
                id = "sess_${UUID.randomUUID().toString().take(8)}",
                status = SessionStatus.PENDING,
                requestedDevices = grantedDevices,
                allocatedDevices = 0,
                api = apiSelector.rawValue,
                deviceType = normalizedDeviceType,
                adbServers = emptyList(),
                createdAt = now,
                expiresAt = now.plusSeconds(effectiveTtlSeconds),
                lastHeartbeatAt = now,
                releasedAt = null,
                ownerId = actor.id,
                ownerName = actor.name,
                name = options.name?.trim()?.takeIf { name -> name.isNotEmpty() },
                metadata = options.metadata,
                priority = options.priority,
                idleTimeoutSeconds = options.idleTimeoutSeconds,
                labels = options.labels,
                deviceIds = options.deviceIds.distinct()
            ).also { created -> stateStore.saveSession(created) }
        }

        logger.info(
            "Created session {} for {}: maxDevices={}, api={}, deviceType={}, ttl={}s",
            session.id,
            actor.name,
            session.requestedDevices,
            session.api ?: "any",
            session.deviceType ?: "all",
            effectiveTtlSeconds
        )
        metrics.sessionCreated(normalizedDeviceType)
        audit.record(actor, AuditActions.SESSION_CREATE, target = session.id, details = creationDetails(session, effectiveTtlSeconds))
        events.publish(EventTypes.SESSION_CREATED, eventData(session))
        return attemptReadyAllocation(session.id) ?: session
    }

    private fun creationDetails(session: Session, ttlSeconds: Long): Map<String, String> = buildMap {
        put("maxDevices", session.requestedDevices.toString())
        put("api", session.api ?: "any")
        put("deviceType", session.deviceType ?: "any")
        put("ttlSeconds", ttlSeconds.toString())
        session.name?.let { name -> put("name", name) }
        if (session.priority != 0) put("priority", session.priority.toString())
        if (session.labels.isNotEmpty()) put("labels", session.labels.entries.joinToString(",") { (key, value) -> "$key=$value" })
        if (session.deviceIds.isNotEmpty()) put("deviceIds", session.deviceIds.joinToString(","))
    }

    private fun validateOptions(options: SessionOptions, actor: Actor) {
        options.name?.let { name -> require(name.length <= MAX_NAME_LENGTH) { "name must be at most $MAX_NAME_LENGTH characters" } }
        require(options.metadata.size <= MAX_METADATA_ENTRIES) { "metadata may hold at most $MAX_METADATA_ENTRIES entries" }
        options.metadata.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= MAX_METADATA_KEY_LENGTH && value.length <= MAX_METADATA_VALUE_LENGTH) {
                "metadata keys must be 1-$MAX_METADATA_KEY_LENGTH characters and values at most $MAX_METADATA_VALUE_LENGTH"
            }
        }
        require(options.labels.size <= MAX_LABELS) { "at most $MAX_LABELS labels can be required" }
        require(options.deviceIds.size <= MAX_DEVICE_IDS) { "at most $MAX_DEVICE_IDS device ids can be listed" }
        options.deviceIds.forEach { id ->
            require(DeviceIds.parse(id) != null) { "Device id '$id' must look like '<provider>:<device>'" }
        }
        options.idleTimeoutSeconds?.let { idle ->
            require(idle >= MIN_IDLE_TIMEOUT_SECONDS) { "idleTimeoutSeconds must be at least $MIN_IDLE_TIMEOUT_SECONDS" }
        }
        require(options.priority in 0..MAX_SESSION_PRIORITY) { "priority must be between 0 and $MAX_SESSION_PRIORITY" }
        val allowedPriority: Int = actor.quota.maxPriority ?: if (actor.isAdmin) MAX_SESSION_PRIORITY else 0
        if (options.priority > allowedPriority) {
            throw AccessDeniedException("priority ${options.priority} exceeds the limit of $allowedPriority for '${actor.name}'")
        }
    }

    private fun rejectUnknownDeviceIds(deviceIds: List<String>) {
        if (deviceIds.isEmpty()) {
            return
        }
        val known: Set<String> = providerCatalog.activeProviders()
            .flatMap { provider -> provider.devices.map { device -> DeviceIds.global(provider.name, device.id) } }
            .toSet()
        val unknown: List<String> = deviceIds.filterNot { id -> id in known }
        require(unknown.isEmpty()) {
            "Unknown device id(s): ${unknown.joinToString()}. List devices with GET /api/v1/devices."
        }
    }

    /** How many of [requestedDevices] the actor's device cap still allows; throws when none. */
    private suspend fun grantDevices(actor: Actor, requestedDevices: Int): Int {
        val limit: Int = actor.quota.maxDevices ?: return requestedDevices
        val inUse: Int = stateStore.usageOf(actor.id).devices
        val remaining: Int = limit - inUse
        if (remaining <= 0) {
            metrics.sessionRejected(REJECTED_QUOTA)
            audit.record(
                actor,
                AuditActions.SESSION_CREATE,
                outcome = AuditOutcome.DENIED,
                details = mapOf("reason" to REJECTED_QUOTA, "inUse" to inUse.toString(), "limit" to limit.toString())
            )
            throw QuotaExceededException(
                "Device quota exhausted: '${actor.name}' already holds or waits for $inUse of $limit device(s). " +
                    "Release a session first."
            )
        }
        // maxDevices is an upper bound for parallelism, so trimming it keeps the request useful.
        return minOf(requestedDevices, remaining)
    }

    private suspend fun <T> withQuotaLock(actor: Actor, block: suspend () -> T): T {
        if (actor.quota.maxDevices == null) {
            return block()
        }
        return quotaLocks.computeIfAbsent(actor.id) { Mutex() }.withLock { block() }
    }

    private suspend fun requireCanManage(actor: Actor, session: Session, action: String?) {
        if (actor.canManage(session)) {
            return
        }
        if (action != null) {
            audit.record(actor, action, target = session.id, outcome = AuditOutcome.DENIED)
        }
        throw AccessDeniedException(
            "Session ${session.id} belongs to ${session.ownerName ?: "another client"}; " +
                "only its owner or an admin can change it"
        )
    }

    suspend fun waitForSession(
        sessionId: String,
        timeoutSeconds: Long = DEFAULT_WAIT_TIMEOUT_SECONDS,
        actor: Actor = Actor.SYSTEM
    ): Session {
        require(timeoutSeconds > 0) { "timeoutSeconds must be greater than zero" }
        val target: Session = stateStore.getSession(sessionId)
            ?: throw ResourceNotFoundException("Session $sessionId not found")
        requireCanManage(actor, target, action = null)
        val clampedTimeoutSeconds: Long = timeoutSeconds.coerceAtMost(MAX_WAIT_TIMEOUT_SECONDS)
        val deadline: Instant = Instant.now().plusSeconds(clampedTimeoutSeconds)

        while (true) {
            cleanupStalePendingSessions()
            val session: Session = stateStore.getSession(sessionId)
                ?: throw IllegalStateException("Session $sessionId not found")
            stateStore.touchSessionHeartbeat(sessionId, Instant.now())

            if (session.status != SessionStatus.PENDING) {
                return session
            }

            val readySession: Session? = attemptReadyAllocation(sessionId)
            if (readySession != null) {
                return readySession
            }

            val refreshedSession: Session = stateStore.getSession(sessionId)
                ?: throw IllegalStateException("Session $sessionId not found")
            if (refreshedSession.status != SessionStatus.PENDING || Instant.now() >= deadline) {
                return refreshedSession
            }

            delay(WAIT_POLL_INTERVAL_MS)
        }
    }

    /** Proves the owner is still there: keeps a queued session in line and a ready one from idling out. */
    suspend fun heartbeat(sessionId: String, actor: Actor = Actor.SYSTEM): Session {
        val session: Session = stateStore.getSession(sessionId)
            ?: throw ResourceNotFoundException("Session $sessionId not found")
        requireCanManage(actor, session, action = null)
        if (session.status in ACTIVE_STATUSES) {
            stateStore.touchSessionHeartbeat(sessionId, Instant.now())
        }
        return stateStore.getSession(sessionId) ?: session
    }

    /**
     * Sets the session's remaining lifetime to [ttlSeconds] from now, within the actor's
     * lifetime cap. A ready session's leases are renewed first; providers that cannot renew
     * make the whole call fail rather than leave the session outliving its devices.
     */
    suspend fun extendSession(sessionId: String, ttlSeconds: Long, actor: Actor = Actor.SYSTEM): Session {
        require(ttlSeconds > 0) { "ttlSeconds must be greater than zero" }
        val session: Session = stateStore.getSession(sessionId)
            ?: throw ResourceNotFoundException("Session $sessionId not found")
        requireCanManage(actor, session, AuditActions.SESSION_EXTEND)
        if (session.status !in ACTIVE_STATUSES) {
            throw ConflictException("Session $sessionId is ${session.status}; only PENDING or READY sessions can be extended")
        }

        val now = Instant.now()
        val requested: Instant = now.plusSeconds(ttlSeconds)
        val lifetimeCap: Long? = actor.quota.maxSessionLifetimeSeconds
        val limit: Instant? = lifetimeCap?.let { cap -> session.createdAt.plusSeconds(cap) }
        val expiresAt: Instant = if (limit != null && requested.isAfter(limit)) limit else requested
        if (!expiresAt.isAfter(now)) {
            throw QuotaExceededException("Session $sessionId reached the lifetime limit of ${lifetimeCap}s for '${actor.name}'")
        }
        if (session.status == SessionStatus.READY) {
            renewLeases(session, Duration.between(now, expiresAt).seconds.coerceAtLeast(1))
        }
        stateStore.updateSessionExpiry(sessionId, expiresAt)
        stateStore.touchSessionHeartbeat(sessionId, now)
        val updated: Session = stateStore.getSession(sessionId)
            ?: throw ResourceNotFoundException("Session $sessionId not found")

        logger.info("Session {} extended by {} until {}", sessionId, actor.name, expiresAt)
        audit.record(actor, AuditActions.SESSION_EXTEND, target = sessionId, details = mapOf("expiresAt" to expiresAt.toString()))
        events.publish(EventTypes.SESSION_EXTENDED, eventData(updated))
        return updated
    }

    private suspend fun renewLeases(session: Session, ttlSeconds: Long) {
        val targets = stateStore.getSessionLeases(session.id).map { lease ->
            val provider = providerCatalog.resolveProvider(lease.providerName)
                ?: throw ConflictException("Provider '${lease.providerName}' holding session ${session.id} is no longer configured")
            lease to provider
        }
        // Check every provider before renewing any, so a refusal cannot leave half the leases extended.
        val cannotRenew: List<String> = targets
            .map { (_, provider) -> provider }
            .filterNot { provider -> provider.supportsFeature(FEATURE_LEASE_RENEW) }
            .map { provider -> provider.name }
            .distinct()
        if (cannotRenew.isNotEmpty()) {
            throw ConflictException(
                "Provider(s) ${cannotRenew.joinToString()} cannot extend leases; " +
                    "ask for a longer ttlSeconds when creating the session"
            )
        }
        for ((lease, provider) in targets) {
            if (!provider.renew(lease.leaseId, ttlSeconds)) {
                throw ConflictException("Provider '${provider.name}' refused to renew lease ${lease.leaseId}")
            }
        }
    }

    suspend fun getSession(sessionId: String): Session? {
        return stateStore.getSession(sessionId)
    }

    suspend fun releaseSession(sessionId: String, actor: Actor = Actor.SYSTEM): Boolean {
        val session: Session = stateStore.getSession(sessionId) ?: return false
        requireCanManage(actor, session, AuditActions.SESSION_RELEASE)
        val released: Boolean = releaseSessionWithStatus(sessionId, SessionStatus.RELEASED)
        if (released && session.status !in FINISHED_STATUSES) {
            audit.record(actor, AuditActions.SESSION_RELEASE, target = sessionId)
        }
        return released
    }

    /** Releases every active session a client owns, e.g. when its key is revoked. */
    suspend fun releaseSessionsOwnedBy(ownerId: String, actor: Actor): Int {
        val owned: List<Session> = stateStore.listSessions(statusFilter = null, ownerId = ownerId)
            .filter { session -> session.status in ACTIVE_STATUSES }
        owned.forEach { session -> releaseSession(session.id, actor) }
        return owned.size
    }

    suspend fun listSessions(statusFilter: String? = null, ownerId: String? = null): List<Session> {
        val filter = statusFilter?.let { runCatching { SessionStatus.valueOf(it.uppercase()) }.getOrNull() }
        return stateStore.listSessions(filter, ownerId)
    }

    suspend fun usageOf(ownerId: String): OwnerUsage = stateStore.usageOf(ownerId)

    suspend fun hasActiveSessionsForProvider(providerName: String): Boolean {
        return stateStore.hasActiveLeasesForProvider(providerName)
    }

    suspend fun cleanupExpiredSessions() {
        cleanupStalePendingSessions()
        for (session in stateStore.listExpiredSessions()) {
            logger.info("Cleaning up expired session {}", session.id)
            releaseSessionWithStatus(session.id, SessionStatus.EXPIRED, reason = "ttl")
            audit.record(
                Actor.SYSTEM,
                AuditActions.SESSION_EXPIRE,
                target = session.id,
                details = ownerDetails(session) + ("reason" to "ttl")
            )
        }
        releaseIdleSessions()
    }

    /** READY sessions that asked for an idle timeout and stopped sending heartbeats. */
    private suspend fun releaseIdleSessions() {
        val now = Instant.now()
        for (session in stateStore.listIdleCandidates()) {
            val idleTimeout: Long = session.idleTimeoutSeconds ?: continue
            if (session.lastHeartbeatAt.plusSeconds(idleTimeout).isAfter(now)) {
                continue
            }
            logger.info("Releasing idle session {}: no heartbeat since {}", session.id, session.lastHeartbeatAt)
            releaseSessionWithStatus(session.id, SessionStatus.EXPIRED, reason = "idle")
            audit.record(
                Actor.SYSTEM,
                AuditActions.SESSION_EXPIRE,
                target = session.id,
                details = ownerDetails(session) + ("reason" to "idle")
            )
        }
    }

    suspend fun getQueuePosition(sessionId: String): Int? {
        cleanupStalePendingSessions()
        val session: Session = stateStore.getSession(sessionId) ?: return null
        if (session.status != SessionStatus.PENDING) {
            return null
        }
        val pendingSessions: List<Session> = stateStore.listPendingSessions(queuePolicy())
        val sessionIndex: Int = pendingSessions.indexOfFirst { pending -> pending.id == sessionId }
        return if (sessionIndex >= 0) sessionIndex + 1 else null
    }

    private suspend fun attemptReadyAllocation(sessionId: String): Session? {
        val session: Session = stateStore.getSession(sessionId) ?: return null
        if (session.status != SessionStatus.PENDING) {
            return session
        }
        if (!isHeadOfQueue(sessionId)) {
            return null
        }
        return allocatePendingSession(session)
    }

    private suspend fun allocatePendingSession(session: Session): Session? {
        val request = DeviceRequest(session.deviceType, ApiSelector.parse(session.api), session.labels, session.deviceIds.toSet())
        val excluded: Set<String> = maintenanceDeviceIds()
        val providers: List<DeviceProvider> = refreshMatchingProviders(session.deviceType)
        if (!ProviderMatcher.hasRegisteredMatchingDevices(providers, request, excluded)) {
            logger.warn("Session {} no longer has any matching registered devices", session.id)
            return null
        }

        val leases = mutableListOf<SessionLease>()
        try {
            var remainingDevices: Int = session.requestedDevices
            for (provider in providers) {
                if (remainingDevices <= 0) {
                    break
                }
                remainingDevices -= when {
                    ProviderMatcher.selectsDevices(provider) ->
                        acquireSelectedDevices(session, provider, request, excluded, remainingDevices, leases)
                    request.isTargeted -> 0
                    else -> acquireFromPool(session, provider, request.apiSelector, remainingDevices, leases)
                }
            }

            val totalAllocated: Int = leases.sumOf { lease -> lease.count }
            if (totalAllocated <= 0) {
                return null
            }

            val readySession = session.copy(
                status = SessionStatus.READY,
                allocatedDevices = totalAllocated,
                adbServers = leases.flatMap { lease -> lease.adbServers }.distinct(),
                devices = leases.flatMap { lease -> lease.devices },
                lastHeartbeatAt = Instant.now()
            )
            stateStore.updateSession(readySession)
            metrics.sessionAllocated(Duration.between(session.createdAt, Instant.now()), totalAllocated)
            logger.info(
                "Session {} ready with {} of {} requested device(s)",
                readySession.id,
                readySession.allocatedDevices,
                readySession.requestedDevices
            )
            events.publish(EventTypes.SESSION_READY, eventData(readySession))
            return readySession
        } catch (error: Exception) {
            logger.error("Failed to allocate session {}", session.id, error)
            rollbackFailedSession(session, leases)
            throw when (error) {
                is IllegalArgumentException -> error
                is IllegalStateException -> error
                else -> IllegalStateException("Failed to allocate session ${session.id}", error)
            }
        }
    }

    /** Allocates from a provider that reports only pool counts; returns how many devices it gave. */
    private suspend fun acquireFromPool(
        session: Session,
        provider: DeviceProvider,
        apiSelector: ApiSelector,
        wanted: Int,
        leases: MutableList<SessionLease>
    ): Int {
        val poolStatus = provider.queryDevices()
        val onDemandProvider: Boolean = ProviderMatcher.supportsOnDemandAllocation(provider)
        if (poolStatus.available <= 0 && !onDemandProvider) {
            logger.info(
                "Skipping provider '{}' for session {}: available=0 busy={} total={} onDemand={}",
                provider.name,
                session.id,
                poolStatus.busy,
                poolStatus.total,
                onDemandProvider
            )
            return 0
        }
        val candidateApiLevels: List<String> = ProviderMatcher.resolveCandidateApiLevels(provider, apiSelector)
        if (candidateApiLevels.isEmpty()) {
            logger.info(
                "Skipping provider '{}' for session {}: no candidate api levels for selector={} supported={} inventoryApis={}",
                provider.name,
                session.id,
                session.api ?: "any",
                provider.capabilities.supportedApiLevels,
                provider.inventory.mapNotNull { profile -> profile.apiLevel }.distinct()
            )
            return 0
        }

        var providerRemaining: Int = if (onDemandProvider) wanted else minOf(wanted, poolStatus.available)
        var acquired = 0
        for (apiLevel in candidateApiLevels) {
            if (providerRemaining <= 0) {
                break
            }
            val ttlSeconds: Long = remainingTtlSeconds(session.expiresAt)
            logger.info(
                "Requesting up to {} device(s) from provider '{}' for session {} at api={} ttl={}s onDemand={} pool={}/{}/{}",
                providerRemaining,
                provider.name,
                session.id,
                apiLevel,
                ttlSeconds,
                onDemandProvider,
                poolStatus.available,
                poolStatus.busy,
                poolStatus.total
            )
            val result = provider.acquire(count = providerRemaining, apiLevel = apiLevel, ttlSeconds = ttlSeconds)
            if (result.acquiredCount <= 0) {
                logger.warn(
                    "Provider '{}' returned 0 device(s) for session {} at api={} requested={} onDemand={}",
                    provider.name,
                    session.id,
                    apiLevel,
                    providerRemaining,
                    onDemandProvider
                )
                continue
            }
            recordLease(session, provider, result, known = emptyMap(), leases = leases)
            providerRemaining -= result.acquiredCount
            acquired += result.acquiredCount
            logger.info(
                "Provider '{}' acquired {} device(s) for session {} at api={}",
                provider.name,
                result.acquiredCount,
                session.id,
                apiLevel
            )
        }
        return acquired
    }

    /**
     * Allocates named devices from a provider that lists them. Only devices that are
     * available, match the request and are not in maintenance are offered, grouped by API
     * level; the adapter picks among them and skips any that changed state since its status.
     */
    private suspend fun acquireSelectedDevices(
        session: Session,
        provider: DeviceProvider,
        request: DeviceRequest,
        excluded: Set<String>,
        wanted: Int,
        leases: MutableList<SessionLease>
    ): Int {
        val candidates: List<AdapterDevice> = ProviderMatcher.availableDevices(provider, request, excluded)
            .filter { device -> device.apiLevel != null }
        if (candidates.isEmpty()) {
            logger.info("Skipping provider '{}' for session {}: no available matching device", provider.name, session.id)
            return 0
        }
        val excludedHere: List<String> = excluded.mapNotNull { id ->
            DeviceIds.parse(id)?.takeIf { (owner, _) -> owner == provider.name }?.second
        }
        val byLevel: Map<String, List<AdapterDevice>> = candidates.groupBy { device -> checkNotNull(device.apiLevel) }
        val preferred: List<String> = ProviderMatcher.resolveCandidateApiLevels(provider, request.apiSelector)
        val levels: List<String> = preferred.filter { level -> level in byLevel } + byLevel.keys.filterNot { level -> level in preferred }

        var remaining: Int = wanted
        var acquired = 0
        for (level in levels) {
            if (remaining <= 0) {
                break
            }
            val group: List<AdapterDevice> = byLevel.getValue(level)
            val selection = DeviceSelection(
                deviceIds = group.map { device -> device.id },
                excludeDeviceIds = excludedHere,
                labels = request.labels,
                sessionId = session.id
            )
            val result = provider.acquire(
                count = minOf(remaining, group.size),
                apiLevel = level,
                ttlSeconds = remainingTtlSeconds(session.expiresAt),
                selection = selection
            )
            if (result.acquiredCount <= 0) {
                logger.info("Provider '{}' gave no device for session {} at api={}", provider.name, session.id, level)
                continue
            }
            recordLease(session, provider, result, known = group.associateBy { device -> device.id }, leases = leases)
            remaining -= result.acquiredCount
            acquired += result.acquiredCount
            logger.info(
                "Provider '{}' acquired {} device(s) for session {} at api={}",
                provider.name,
                result.acquiredCount,
                session.id,
                level
            )
        }
        return acquired
    }

    private suspend fun recordLease(
        session: Session,
        provider: DeviceProvider,
        result: AcquireResult,
        known: Map<String, AdapterDevice>,
        leases: MutableList<SessionLease>
    ) {
        val devices: List<SessionDevice> = result.devices.map { leased ->
            val details: AdapterDevice? = known[leased.id] ?: provider.devices.firstOrNull { device -> device.id == leased.id }
            SessionDevice(
                id = DeviceIds.global(provider.name, leased.id),
                provider = provider.name,
                localId = leased.id,
                adbServer = leased.adbServer,
                model = details?.model,
                apiLevel = details?.apiLevel
            )
        }
        // Register the lease for rollback before anything that can still fail.
        val lease = SessionLease(provider.name, result.leaseId, result.acquiredCount, adbServers = emptyList(), devices = devices)
        leases += lease
        leases[leases.lastIndex] = lease.copy(adbServers = resolveLeaseAdbServers(provider, result))
        stateStore.saveSessionLease(session.id, provider.name, result.leaseId, result.acquiredCount)
    }

    private suspend fun rollbackFailedSession(session: Session, leases: List<SessionLease>) {
        val sessionId: String = session.id
        for (lease in leases.asReversed()) {
            val provider = providerCatalog.resolveProvider(lease.providerName)
            if (provider == null) {
                logger.warn("Provider '{}' not found during rollback of {}", lease.providerName, sessionId)
                continue
            }
            try {
                withTimeout(RELEASE_TIMEOUT_MS) { provider.release(lease.leaseId) }
                logger.info("Rolled back lease {} on '{}'", lease.leaseId, lease.providerName)
            } catch (error: TimeoutCancellationException) {
                logger.error("Timed out rolling back lease {} on '{}'", lease.leaseId, lease.providerName)
            } catch (error: Exception) {
                logger.error("Failed to roll back lease {} on '{}'", lease.leaseId, lease.providerName, error)
            }
        }
        stateStore.updateSessionStatus(sessionId, FAILED)
        metrics.sessionFinished(FAILED, Duration.between(session.createdAt, Instant.now()))
        audit.record(
            Actor.SYSTEM,
            AuditActions.SESSION_FAIL,
            target = sessionId,
            details = ownerDetails(session) + mapOf("reason" to "allocation error")
        )
        events.publish(EventTypes.SESSION_FAILED, eventData(session.copy(status = FAILED), reason = "allocation error"))
    }

    private suspend fun cleanupStalePendingSessions() {
        val staleBefore: Instant = Instant.now().minusSeconds(STALE_PENDING_HEARTBEAT_SECONDS)
        val staleSessions: List<Session> = stateStore.listStalePendingSessions(staleBefore)
        for (session in staleSessions) {
            logger.warn(
                "Cleaning up stale pending session {} (last heartbeat at {})",
                session.id,
                session.lastHeartbeatAt
            )
            releaseSessionWithStatus(session.id, SessionStatus.FAILED, reason = "no heartbeat while queued")
            audit.record(
                Actor.SYSTEM,
                AuditActions.SESSION_FAIL,
                target = session.id,
                details = ownerDetails(session) + mapOf("reason" to "no wait heartbeat while queued")
            )
        }
    }

    private fun ownerDetails(session: Session): Map<String, String> =
        session.ownerName?.let { owner -> mapOf("owner" to owner) } ?: emptyMap()

    private fun eventData(session: Session, reason: String? = null): JsonObject = buildJsonObject {
        put("sessionId", session.id)
        put("status", session.status.name)
        session.ownerName?.let { owner -> put("owner", owner) }
        session.name?.let { name -> put("name", name) }
        put("requestedDevices", session.requestedDevices)
        put("allocatedDevices", session.allocatedDevices)
        put("expiresAt", session.expiresAt.toString())
        putJsonArray("devices") { session.devices.forEach { device -> add(device.id) } }
        reason?.let { text -> put("reason", text) }
    }

    private suspend fun refreshMatchingProviders(deviceType: String?): List<DeviceProvider> {
        return providerCatalog.activeProviders()
            .filter { provider -> deviceType == null || provider.supportsDeviceType(deviceType) }
            .onEach { provider ->
                runCatching { provider.queryDevices() }
                    .onFailure { error ->
                        logger.warn("Provider '{}' inventory refresh failed: {}", provider.name, error.message)
                    }
            }
    }

    private suspend fun isHeadOfQueue(sessionId: String): Boolean {
        val pendingSessions: List<Session> = stateStore.listPendingSessions(queuePolicy())
        return pendingSessions.firstOrNull()?.id == sessionId
    }

    private fun normalizeDeviceType(rawDeviceType: String?): String? {
        val normalizedDeviceType: String? = rawDeviceType
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { value -> value.isNotEmpty() }
        require(normalizedDeviceType == null || normalizedDeviceType in SUPPORTED_DEVICE_TYPES) {
            "Unsupported deviceType '$rawDeviceType'. Supported values: $SUPPORTED_DEVICE_TYPES_TEXT"
        }
        return normalizedDeviceType
    }

    private fun remainingTtlSeconds(expiresAt: Instant): Long {
        return Duration.between(Instant.now(), expiresAt).seconds.coerceAtLeast(1L)
    }

    private fun resolveLeaseAdbServers(provider: DeviceProvider, result: AcquireResult): List<AdbServer> {
        if (result.adbServers.isNotEmpty()) {
            return result.adbServers
        }
        val accessIsolation: String? = provider.capabilities.metadata["accessIsolation"]
        require(accessIsolation != "lease-scoped-proxy") {
            "Provider '${provider.name}' acquired devices but did not return lease-scoped adbServers"
        }
        return listOf(provider.adbServer)
    }

    private suspend fun releaseSessionWithStatus(sessionId: String, targetStatus: SessionStatus, reason: String? = null): Boolean {
        val session = stateStore.getSession(sessionId) ?: return false
        if (session.status == SessionStatus.RELEASED || session.status == SessionStatus.EXPIRED) {
            logger.info("Session {} already {}", sessionId, session.status)
            return true
        }

        logger.info("Releasing session {} -> {}", sessionId, targetStatus)
        val leases = stateStore.getSessionLeases(sessionId)
        for (lease in leases) {
            val provider = providerCatalog.resolveProvider(lease.providerName)
            if (provider == null) {
                logger.warn("Provider '{}' not found, skipping release", lease.providerName)
                continue
            }
            try {
                withTimeout(RELEASE_TIMEOUT_MS) { provider.release(lease.leaseId) }
                logger.info("Released lease {} on '{}'", lease.leaseId, lease.providerName)
            } catch (error: TimeoutCancellationException) {
                logger.error("Timed out releasing lease {} on '{}'", lease.leaseId, lease.providerName)
            } catch (error: Exception) {
                logger.error("Failed to release lease {} on '{}'", lease.leaseId, lease.providerName, error)
            }
        }

        stateStore.updateSessionStatus(sessionId, targetStatus)
        // A FAILED session lingers until it expires; count and announce it once, when it left the queue.
        if (session.status in ACTIVE_STATUSES) {
            metrics.sessionFinished(targetStatus, Duration.between(session.createdAt, Instant.now()))
            val eventType: String? = when (targetStatus) {
                SessionStatus.RELEASED -> EventTypes.SESSION_RELEASED
                SessionStatus.EXPIRED -> EventTypes.SESSION_EXPIRED
                SessionStatus.FAILED -> EventTypes.SESSION_FAILED
                else -> null
            }
            eventType?.let { type -> events.publish(type, eventData(session.copy(status = targetStatus), reason)) }
        }
        return true
    }
}

private data class SessionLease(
    val providerName: String,
    val leaseId: String,
    val count: Int,
    val adbServers: List<AdbServer>,
    val devices: List<SessionDevice> = emptyList()
)
