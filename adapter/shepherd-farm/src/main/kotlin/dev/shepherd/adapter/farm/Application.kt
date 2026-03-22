package dev.shepherd.adapter.farm

import dev.shepherd.adapter.api.AdapterEnv
import dev.shepherd.adapter.api.startAdapterServer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

private const val DEFAULT_FARM_SERVER_HOST: String = "127.0.0.1"
private const val DEFAULT_FARM_SERVER_PORT: Int = 8080

fun main() {
    val env: AdapterEnv = AdapterEnv.fromEnvironment(defaultPort = 7037)
    val farmServerUrl: String = resolveFarmServerUrl()
    val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    startAdapterServer(
        FarmAdapterHandler(
            farmClient = FarmServerClient(farmServerUrl, httpClient)
        ),
        env
    )
}

private fun resolveFarmServerUrl(): String {
    val host: String = System.getenv("FARM_SERVER_HOST").orEmpty().ifBlank { DEFAULT_FARM_SERVER_HOST }
    val port: Int = System.getenv("FARM_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_FARM_SERVER_PORT
    require(port in 1..65_535) { "FARM_SERVER_PORT must be between 1 and 65535" }
    return "http://$host:$port"
}
