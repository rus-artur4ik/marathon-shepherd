package dev.shepherd.api

import dev.shepherd.configureServer
import dev.shepherd.domain.DeviceAllocator
import dev.shepherd.domain.SessionManager
import dev.shepherd.infra.state.StateStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DeviceRoutesTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `devices endpoint should aggregate totals and expose provider descriptors`() = testApplication {
        val providerOne = RouteTestProvider(
            name = "rack-1",
            availableDevices = 1,
            inventory = listOf(
                dev.shepherd.adapter.api.AdapterDeviceProfile(
                    deviceType = dev.shepherd.adapter.api.DEVICE_TYPE_PHYSICAL,
                    apiLevel = "34",
                    manufacturer = "Google",
                    model = "Pixel",
                    abi = "arm64-v8a",
                    count = 2
                )
            ),
            supportedDeviceTypes = listOf(dev.shepherd.adapter.api.DEVICE_TYPE_PHYSICAL)
        )
        val providerTwo = RouteTestProvider(
            name = "farm-1",
            availableDevices = 3,
            inventory = listOf(
                dev.shepherd.adapter.api.AdapterDeviceProfile(
                    deviceType = dev.shepherd.adapter.api.DEVICE_TYPE_EMULATOR,
                    apiLevel = "35",
                    manufacturer = "Google",
                    model = "Cuttlefish",
                    abi = "x86_64",
                    count = 4
                )
            )
        )
        val providerRegistry = createRouteProviderRegistry(tempDir, "devices.yaml", listOf(providerOne, providerTwo))
        val stateStore = StateStore(File(tempDir, "devices.db").absolutePath)

        application {
            configureServer(
                sessionManager = SessionManager(providerRegistry, stateStore),
                deviceAllocator = DeviceAllocator(providerRegistry),
                providerRegistry = providerRegistry
            )
        }

        val response = client.get("/api/v1/devices")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "\"totalAvailable\": 4")
        assertContains(body, "\"totalBusy\": 2")
        assertContains(body, "\"name\": \"rack-1\"")
        assertContains(body, "\"name\": \"farm-1\"")
        assertContains(body, "\"capabilities\"")
        assertContains(body, "\"inventory\"")
        assertContains(body, "\"access\"")
    }
}
