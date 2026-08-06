package dev.shepherd.domain.provider

import dev.shepherd.adapter.api.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteAdapterProviderTest {

    @Test
    fun `should rewrite direct adb host to manager configured access host`() {
        val inputAccess = AdapterAccess(
            preferredConnectionId = "primary",
            connections = listOf(
                AdapterConnection(
                    id = "primary",
                    protocol = ACCESS_PROTOCOL_ADB,
                    transport = ACCESS_TRANSPORT_TCP,
                    host = "adapter.internal",
                    port = 5037,
                    exposure = ACCESS_EXPOSURE_DIRECT_TCP,
                    auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK)
                )
            )
        )

        val actualAccess = normalizeAccessHost(inputAccess, "devices.internal")

        assertEquals("devices.internal", actualAccess.connections.single().host)
        assertEquals("devices.internal", actualAccess.metadata["resolvedAccessHost"])
    }

    @Test
    fun `should keep non direct tcp access untouched`() {
        val inputAccess = AdapterAccess(
            preferredConnectionId = "primary",
            connections = listOf(
                AdapterConnection(
                    id = "primary",
                    protocol = ACCESS_PROTOCOL_ADB,
                    transport = ACCESS_TRANSPORT_TCP,
                    host = "adapter.internal",
                    port = 5037,
                    exposure = "proxy",
                    auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK)
                )
            )
        )

        val actualAccess = normalizeAccessHost(inputAccess, "devices.internal")

        assertEquals("adapter.internal", actualAccess.connections.single().host)
    }
}
