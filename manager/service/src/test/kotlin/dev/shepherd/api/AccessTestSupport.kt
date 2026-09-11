package dev.shepherd.api

import dev.shepherd.ManagerServices
import dev.shepherd.adapter.api.AdapterDeviceProfile
import dev.shepherd.adapter.api.DEVICE_TYPE_EMULATOR
import dev.shepherd.configureServer
import dev.shepherd.infra.state.StateStore
import dev.shepherd.protocol.SessionResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import java.io.File

internal val TestJson = Json { ignoreUnknownKeys = true }

/** A manager with one emulator provider holding [devices] API-34 devices; [extraConfig] is appended to msh.yaml. */
internal fun ApplicationTestBuilder.startManager(
    tempDir: File,
    name: String,
    devices: Int = 10,
    extraConfig: String = ""
): ManagerServices {
    val provider = RouteTestProvider(
        availableDevices = devices,
        inventory = listOf(AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = "34", count = devices))
    )
    val configFile = File(tempDir, "$name.yaml")
    configFile.writeText(
        """
        |providers:
        |  - name: "${provider.name}"
        |    url: "http://127.0.0.1:7037"
        |$extraConfig
        """.trimMargin()
    )
    val services =
        managerServices(createRouteProviderRegistry(configFile, listOf(provider)), StateStore(File(tempDir, "$name.db").absolutePath))
    application { configureServer(services) }
    return services
}

internal suspend fun ApplicationTestBuilder.createSession(key: String, maxDevices: Int = 1, ttlSeconds: Long = 600): HttpResponse =
    client.post("/api/v1/sessions") {
        bearerAuth(key)
        contentType(ContentType.Application.Json)
        setBody("""{"maxDevices":$maxDevices,"api":"34","deviceType":"emulator","ttlSeconds":$ttlSeconds}""")
    }

internal suspend fun HttpResponse.session(): SessionResponse = TestJson.decodeFromString(bodyAsText())
