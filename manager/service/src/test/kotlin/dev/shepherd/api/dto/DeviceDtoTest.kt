package dev.shepherd.api.dto

import dev.shepherd.adapter.api.*
import dev.shepherd.domain.ProviderStatus
import dev.shepherd.domain.provider.DevicePoolStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceDtoTest {

    @Test
    fun `should use resolved access host when status access has no connections`() {
        val dto = ProviderStatus(
            name = "device-rack-1",
            access = AdapterAccess(
                preferredConnectionId = null,
                connections = emptyList(),
                metadata = mapOf(
                    "adapterType" to "adb",
                    "resolvedAccessHost" to "192.168.50.254"
                )
            ),
            capabilities = AdapterCapabilities(),
            inventory = emptyList(),
            pool = DevicePoolStatus(available = 1, busy = 0, total = 1),
            isHealthy = true
        ).toDto()

        assertEquals("192.168.50.254", dto.adbHost)
        assertEquals(5037, dto.adbPort)
    }

    @Test
    fun `should prefer direct adb connection when available`() {
        val dto = ProviderStatus(
            name = "device-rack-1",
            access = AdapterAccess(
                preferredConnectionId = "primary",
                connections = listOf(
                    AdapterConnection(
                        id = "primary",
                        protocol = ACCESS_PROTOCOL_ADB,
                        transport = ACCESS_TRANSPORT_TCP,
                        host = "192.168.50.10",
                        port = 7600,
                        exposure = ACCESS_EXPOSURE_DIRECT_TCP,
                        auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK)
                    )
                ),
                metadata = mapOf("resolvedAccessHost" to "192.168.50.254")
            ),
            capabilities = AdapterCapabilities(),
            inventory = emptyList(),
            pool = DevicePoolStatus(available = 1, busy = 0, total = 1),
            isHealthy = true
        ).toDto()

        assertEquals("192.168.50.10", dto.adbHost)
        assertEquals(7600, dto.adbPort)
    }
}
