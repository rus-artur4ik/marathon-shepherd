package dev.shepherd.api

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

private val PROMETHEUS_TEXT: ContentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")

/** Prometheus scrape endpoint. Public like the probes; keep the port off untrusted networks. */
fun Route.metricsRoutes(registry: PrometheusMeterRegistry) {
    get("/metrics") {
        call.respondText(registry.scrape(), PROMETHEUS_TEXT)
    }
}
