package dev.shepherd.adapter.adb

import kotlin.test.*

class AdbSmartSocketProxyTest {

    @Test
    fun `should rewrite transport any to the leased serial`() {
        assertEquals("host:transport:serial-1", rewriteTransportCommand("host:transport-any", "serial-1"))
        assertEquals("host:transport:serial-1", rewriteTransportCommand("host:transport-usb", "serial-1"))
    }

    @Test
    fun `should reject transport for a foreign serial`() {
        assertNull(rewriteTransportCommand("host:transport:serial-2", "serial-1"))
        assertNull(validateHostSerialCommand("host-serial:serial-2:get-state", "serial-1"))
    }

    @Test
    fun `should filter device payload down to the leased serial`() {
        val payload = """
            serial-1	device
            serial-2	device
        """.trimIndent()

        val actual = filterDevicesPayload(payload, "serial-2")

        assertEquals("serial-2\tdevice", actual)
    }

    @Test
    fun `should detect streaming host serial commands`() {
        assertTrue(isStreamingHostSerialCommand("host-serial:serial-1:shell:getprop"))
    }

    @Test
    fun `should reserve restored preferred port in the proxy pool`() {
        val pool = AdbProxyPortPool(AdbProxyPortRange(startPort = 7600, endPort = 7601))

        val restoredPort = pool.acquirePort(preferredPort = 7600)

        assertEquals(7600, restoredPort)
        assertFailsWith<IllegalStateException> {
            pool.acquirePort(preferredPort = 7600)
        }
    }
}
