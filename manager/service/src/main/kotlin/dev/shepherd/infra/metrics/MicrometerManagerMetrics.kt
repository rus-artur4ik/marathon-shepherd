package dev.shepherd.infra.metrics

import dev.shepherd.common.BuildInfo
import dev.shepherd.domain.FleetSnapshot
import dev.shepherd.domain.metrics.ManagerMetrics
import dev.shepherd.domain.model.SessionHistory
import dev.shepherd.domain.model.SessionStatus
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration

/**
 * [ManagerMetrics] backed by a Prometheus registry, scraped at `/metrics`.
 *
 * Latency metrics use a short explicit list of buckets rather than Micrometer's default
 * percentile histogram (~70 buckets per series): the manager's label sets multiply by
 * provider and operation, and a handful of buckets is enough to alert on.
 */
class MicrometerManagerMetrics(
    val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
) : ManagerMetrics {

    init {
        // Micrometer applies a filter only to meters registered after it, so this comes before the first one.
        registry.config().meterFilter(HTTP_SERVER_BUCKETS)
    }

    private val sessions: MultiGauge = MultiGauge.builder("msh.sessions")
        .description("Sessions that are queued or holding devices, by status")
        .register(registry)
    private val providerUp: MultiGauge = MultiGauge.builder("msh.provider.up")
        .description("1 when the provider's adapter passed its last health check, else 0")
        .register(registry)
    private val providerDevices: MultiGauge = MultiGauge.builder("msh.provider.devices")
        .description("Devices reported by each provider's last status poll, by state")
        .register(registry)
    private val devices: MultiGauge = MultiGauge.builder("msh.devices")
        .description("Devices of providers that list them individually, by adapter-reported state")
        .register(registry)
    private val storedSessions: MultiGauge = MultiGauge.builder("msh.sessions.stored")
        .description("Sessions in the database by status; finished ones are kept for sessions.retentionDays")
        .register(registry)
    private val queueWait: Timer = Timer.builder("msh.sessions.queue.wait")
        .description("Time from session creation until the session received devices")
        .serviceLevelObjectives(*QUEUE_WAIT_BUCKETS)
        .register(registry)

    @Volatile
    private var allocatedDevices: Double = 0.0

    @Volatile
    private var lastPollEpochSeconds: Double = 0.0

    @Volatile
    private var subscribers: Double = 0.0

    @Volatile
    private var lastRequestEpochSeconds: Double = 0.0

    init {
        Gauge.builder("msh.build.info") { 1.0 }
            .description("Build metadata; the value is always 1")
            .tags("version", BuildInfo.version, "component", "manager")
            .register(registry)
        Gauge.builder("msh.sessions.devices", this) { metrics -> metrics.allocatedDevices }
            .description("Devices currently held by READY sessions")
            .register(registry)
        Gauge.builder("msh.events.subscribers", this) { metrics -> metrics.subscribers }
            .description("Clients connected to the event stream")
            .register(registry)
        Gauge.builder("msh.fleet.last.poll", this) { metrics -> metrics.lastPollEpochSeconds }
            .description("Unix time of the last completed fleet poll")
            .baseUnit("seconds")
            .register(registry)
        // Not "last.created": the Prometheus client strips a `_created` suffix.
        Gauge.builder("msh.sessions.last.request", this) { metrics -> metrics.lastRequestEpochSeconds }
            .description("Unix time the newest session in the database was requested; 0 when there is none")
            .baseUnit("seconds")
            .register(registry)
        // Publish zeros up front so dashboards and alerts see a value, not "no data",
        // before the first poll completes.
        sessions.register(sessionRows(pending = 0, ready = 0), true)
        historyObserved(SessionHistory.EMPTY)
    }

    override fun sessionCreated(deviceType: String?) {
        // Not "created": the Prometheus client strips a `_created` suffix, which would
        // collapse this counter into the `msh_sessions` gauge.
        Counter.builder("msh.sessions.accepted")
            .description("Session requests accepted into the queue")
            .tag("device_type", deviceType ?: "any")
            .register(registry)
            .increment()
    }

    override fun sessionRejected(reason: String) {
        Counter.builder("msh.sessions.rejected")
            .description("Session requests refused before they were queued")
            .tag("reason", reason)
            .register(registry)
            .increment()
    }

    override fun sessionAllocated(queueWait: Duration, devices: Int) {
        this.queueWait.record(queueWait)
        Counter.builder("msh.devices.allocated")
            .description("Devices handed out to sessions")
            .register(registry)
            .increment(devices.toDouble())
    }

    override fun sessionFinished(status: SessionStatus, lifetime: Duration) {
        val statusTag = status.name.lowercase()
        Counter.builder("msh.sessions.finished")
            .description("Sessions that reached a terminal status")
            .tag("status", statusTag)
            .register(registry)
            .increment()
        Timer.builder("msh.sessions.lifetime")
            .description("Time from session creation until it reached a terminal status")
            .tag("status", statusTag)
            .serviceLevelObjectives(*LIFETIME_BUCKETS)
            .register(registry)
            .record(lifetime)
    }

    override fun adapterCall(provider: String, operation: String, outcome: String, duration: Duration) {
        Timer.builder("msh.adapter.requests")
            .description("HTTP calls from the manager to adapters")
            .tags("provider", provider, "operation", operation, "outcome", outcome)
            .serviceLevelObjectives(*ADAPTER_BUCKETS)
            .register(registry)
            .record(duration)
    }

    override fun fleetObserved(snapshot: FleetSnapshot) {
        providerUp.register(
            snapshot.providers.map { provider ->
                MultiGauge.Row.of(Tags.of("provider", provider.name), if (provider.isHealthy) 1 else 0)
            },
            true
        )
        providerDevices.register(
            snapshot.providers.flatMap { provider ->
                listOf(
                    "available" to provider.pool.available,
                    "busy" to provider.pool.busy,
                    "total" to provider.pool.total
                ).map { (state, value) ->
                    MultiGauge.Row.of(Tags.of("provider", provider.name, "state", state), value)
                }
            },
            true
        )
        devices.register(
            snapshot.providers.flatMap { provider ->
                provider.devices
                    .groupBy { device -> device.state to device.deviceType }
                    .map { (key, grouped) ->
                        MultiGauge.Row.of(Tags.of("provider", provider.name, "state", key.first, "device_type", key.second), grouped.size)
                    }
            },
            true
        )
        sessions.register(sessionRows(snapshot.sessions.pending, snapshot.sessions.ready), true)
        allocatedDevices = snapshot.sessions.allocatedDevices.toDouble()
        lastPollEpochSeconds = snapshot.takenAt.toEpochMilli() / 1_000.0
    }

    override fun leaseReclaimed(provider: String) {
        Counter.builder("msh.leases.reclaimed")
            .description("Orphaned adapter leases released by reconciliation")
            .tag("provider", provider)
            .register(registry)
            .increment()
    }

    override fun eventSubscribers(count: Int) {
        subscribers = count.toDouble()
    }

    override fun historyObserved(history: SessionHistory) {
        storedSessions.register(
            SessionStatus.entries.map { status ->
                MultiGauge.Row.of(Tags.of("status", status.name.lowercase()), history.byStatus[status] ?: 0)
            },
            true
        )
        lastRequestEpochSeconds = history.lastRequestedAt?.let { at -> at.toEpochMilli() / 1_000.0 } ?: 0.0
    }

    private fun sessionRows(pending: Int, ready: Int): List<MultiGauge.Row<*>> = listOf(
        MultiGauge.Row.of(Tags.of("status", "pending"), pending),
        MultiGauge.Row.of(Tags.of("status", "ready"), ready)
    )

    companion object {
        private val QUEUE_WAIT_BUCKETS: Array<Duration> = arrayOf(1L, 5L, 15L, 30L, 60L, 120L, 300L, 600L, 900L, 1_800L, 3_600L)
            .map(Duration::ofSeconds).toTypedArray()
        private val LIFETIME_BUCKETS: Array<Duration> = arrayOf(1L, 5L, 15L, 30L, 60L, 120L, 240L, 480L, 1_440L)
            .map(Duration::ofMinutes).toTypedArray()
        private val ADAPTER_BUCKETS: Array<Duration> =
            arrayOf(25L, 50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
                .map(Duration::ofMillis).toTypedArray()

        /** Ktor's timer for the requests it serves (the plugin's default `metricName`). */
        private const val HTTP_SERVER_REQUESTS: String = "ktor.http.server.requests"

        /**
         * Buckets for Ktor's own `ktor.http.server.requests` timer. Session long-polls hold a
         * request for up to 30 s, so the top bucket has to reach that far.
         */
        private val HTTP_SERVER_DISTRIBUTION: DistributionStatisticConfig = DistributionStatisticConfig.builder()
            .percentilesHistogram(false)
            .serviceLevelObjectives(
                *arrayOf(5L, 10L, 25L, 50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 30_000L)
                    .map { millis -> Duration.ofMillis(millis).toNanos().toDouble() }
                    .toDoubleArray()
            )
            .build()

        /**
         * Gives Ktor's timer [HTTP_SERVER_DISTRIBUTION]. It sits on the registry from the start instead of
         * coming from the Ktor plugin, whose filter would arrive after the manager's own meters; install
         * the plugin with `registerDistributionStatisticConfig = false`.
         */
        private val HTTP_SERVER_BUCKETS: MeterFilter = object : MeterFilter {
            override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
                if (id.name == HTTP_SERVER_REQUESTS) HTTP_SERVER_DISTRIBUTION.merge(config) else config
        }
    }
}
