package dev.shepherd.adapter.api

import dev.shepherd.common.BuildInfo
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration

/**
 * Adapter-side metrics, scraped at the adapter's own `/metrics`.
 *
 * Pool gauges are updated from each `/status` answer — the manager polls that every few
 * seconds — instead of a second poll loop that would run `adb` twice as often.
 * `msh_adapter_last_status_seconds` shows when that last happened, so a manager that
 * stopped polling is visible rather than frozen gauges looking healthy.
 */
class AdapterMetrics(
    val adapterType: String,
    val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
) {
    private val pool: MultiGauge = MultiGauge.builder("msh.adapter.pool.devices")
        .description("Devices in this adapter's pool at the last /status answer, by state")
        .register(registry)
    private val acquireDuration: Timer = Timer.builder("msh.adapter.acquire.duration")
        .description("Time to serve one acquire request")
        .serviceLevelObjectives(*ACQUIRE_BUCKETS)
        .register(registry)

    @Volatile
    private var lastStatusEpochSeconds: Double = 0.0

    init {
        // A gauge whose name ends in `.info` is exported as a Prometheus info metric.
        Gauge.builder("msh.build.info") { 1.0 }
            .description("Build metadata; the value is always 1")
            .tags("version", BuildInfo.version, "component", "adapter", "adapter_type", adapterType)
            .register(registry)
        Gauge.builder("msh.adapter.last.status", this) { metrics -> metrics.lastStatusEpochSeconds }
            .description("Unix time of the last /status answer")
            .baseUnit("seconds")
            .register(registry)
        publishPool(AdapterPool(available = 0, busy = 0, total = 0))
    }

    fun statusObserved(pool: AdapterPool) {
        publishPool(pool)
        lastStatusEpochSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
    }

    fun acquireCompleted(requested: Int, acquired: Int, duration: Duration) {
        val outcome: String = when {
            acquired <= 0 -> "empty"
            acquired < requested -> "partial"
            else -> "full"
        }
        countAcquire(outcome)
        if (acquired > 0) {
            Counter.builder("msh.adapter.acquired.devices")
                .description("Devices leased out by this adapter")
                .register(registry)
                .increment(acquired.toDouble())
        }
        acquireDuration.record(duration)
    }

    fun acquireFailed(duration: Duration) {
        countAcquire("error")
        acquireDuration.record(duration)
    }

    fun releaseCompleted(success: Boolean) {
        Counter.builder("msh.adapter.release")
            .description("Lease release requests by outcome")
            .tag("outcome", if (success) "success" else "failure")
            .register(registry)
            .increment()
    }

    private fun countAcquire(outcome: String) {
        Counter.builder("msh.adapter.acquire")
            .description("Acquire requests by outcome")
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    private fun publishPool(pool: AdapterPool) {
        this.pool.register(
            listOf(
                MultiGauge.Row.of(Tags.of("state", "available"), pool.available),
                MultiGauge.Row.of(Tags.of("state", "busy"), pool.busy),
                MultiGauge.Row.of(Tags.of("state", "total"), pool.total)
            ),
            true
        )
    }

    companion object {
        private const val MILLIS_PER_SECOND: Double = 1_000.0
        private val ACQUIRE_BUCKETS: Array<Duration> =
            arrayOf(50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
                .map(Duration::ofMillis).toTypedArray()

        /** Buckets for Ktor's `ktor.http.server.requests`; acquire on an on-demand adapter can take minutes. */
        val HTTP_SERVER_DISTRIBUTION: DistributionStatisticConfig = DistributionStatisticConfig.builder()
            .percentilesHistogram(false)
            .serviceLevelObjectives(
                *arrayOf(5L, 10L, 25L, 50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 30_000L, 120_000L, 600_000L)
                    .map { millis -> Duration.ofMillis(millis).toNanos().toDouble() }
                    .toDoubleArray()
            )
            .build()
    }
}
