package dev.shepherd.adapter.adb

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [AdbProxySession] that verify the full TCP round-trip
 * through [LeaseScopedAdbProxyController].
 *
 * The tests start a real [ServerSocket] as a mock ADB upstream and connect a real
 * [Socket] as a mock ADB client, so the proxy's actual I/O path is exercised.
 *
 * Deadlock regression (transport commands):
 *   [bridgeTransportCommand] writes OKAY to a [java.io.BufferedOutputStream] and
 *   immediately starts [pipeBidirectional]. Without flushing first the client never
 *   receives OKAY, so both pump threads stall — client waits for OKAY, upstream
 *   waits for the shell command the client never sends. The tests below use
 *   [Socket.soTimeout] so the deadlock manifests as [SocketTimeoutException] instead
 *   of an infinite hang.
 */
class AdbProxySessionIntegrationTest {

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Main regression test for the missing-flush deadlock.
     *
     * Flow:
     *   client  → host:transport-any
     *   proxy   → (rewrite) → host:transport:<serial>  → upstream
     *   upstream → OKAY
     *   proxy   → OKAY  → client          ← hangs here without the flush fix
     *   client  → shell:getprop sys.boot_completed
     *   upstream → OKAY + "1\n"
     *   client reads "1\n"
     */
    @Test
    fun `shell command after host transport-any completes without deadlock`() {
        val serial = "test-serial-1"
        val upstream = MockAdbUpstream { conn ->
            val transportCmd = conn.readCommand()
            assertEquals("host:transport:$serial", transportCmd)
            conn.writeRaw("OKAY")

            val shellCmd = conn.readCommand()
            assertEquals("shell:getprop sys.boot_completed", shellCmd)
            conn.writeRaw("OKAY")
            conn.writeRaw("1\n")
        }

        withProxy(upstream, serial) { proxyPort ->
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3_000
                client.sendCommand("host:transport-any")
                assertEquals("OKAY", client.readExact(4)) // deadlock without fix

                client.sendCommand("shell:getprop sys.boot_completed")
                assertEquals("OKAY", client.readExact(4))
                assertEquals("1\n", client.readAll())
            }
        }
        upstream.join()
    }

    /**
     * Variant using explicit serial in the transport command.
     * marathon/adam uses host:transport:<serial> directly.
     */
    @Test
    fun `shell command after host transport with explicit serial completes without deadlock`() {
        val serial = "test-serial-2"
        val upstream = MockAdbUpstream { conn ->
            assertEquals("host:transport:$serial", conn.readCommand())
            conn.writeRaw("OKAY")

            assertEquals("shell:getprop sys.boot_completed", conn.readCommand())
            conn.writeRaw("OKAY")
            conn.writeRaw("1\n")
        }

        withProxy(upstream, serial) { proxyPort ->
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3_000
                client.sendCommand("host:transport:$serial")
                assertEquals("OKAY", client.readExact(4))

                client.sendCommand("shell:getprop sys.boot_completed")
                assertEquals("OKAY", client.readExact(4))
                assertEquals("1\n", client.readAll())
            }
        }
        upstream.join()
    }

    /**
     * Without the fix the OKAY response is stuck in a [java.io.BufferedOutputStream]
     * and the client times out. Demonstrates what the test above would see on a
     * broken build (kept as documentation, not run in CI by default — remove the
     * `@Ignore` comment below to run locally against the unfixed code).
     */
    // @Test  ← intentionally not a test; documents the broken behaviour
    @Suppress("unused")
    fun `BROKEN without fix - shell command after transport-any times out`() {
        // Simulates missing flush: wrap clientOutput in a stream that never flushes
        // automatically. In the real broken build, BufferedOutputStream holds OKAY
        // until something else triggers a flush — which never happens in
        // pipeBidirectional because both threads are stuck waiting for each other.
        val serial = "broken-serial"
        val upstream = MockAdbUpstream { conn ->
            conn.readCommand() // reads and discards; client never sends shell cmd
            conn.writeRaw("OKAY")
            Thread.sleep(500) // simulate upstream waiting
        }
        withProxy(upstream, serial) { proxyPort ->
            assertFailsWith<SocketTimeoutException> {
                Socket("127.0.0.1", proxyPort).use { client ->
                    client.soTimeout = 200
                    client.sendCommand("host:transport-any")
                    client.readExact(4) // times out without fix
                }
            }
        }
        upstream.join()
    }

    /**
     * Verifies the single-device host command path (get-state).
     * This path was not affected by the deadlock but is included as a
     * baseline so regressions in one-shot commands are caught.
     */
    @Test
    fun `get-state via single device host command returns device state`() {
        val serial = "test-serial-3"
        val upstream = MockAdbUpstream { conn ->
            val cmd = conn.readCommand()
            assertEquals("host-serial:$serial:get-state", cmd)
            conn.writeRaw("OKAY")
            conn.writeLengthPrefixed("device")
        }

        withProxy(upstream, serial) { proxyPort ->
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3_000
                client.sendCommand("host:get-state")
                assertEquals("OKAY", client.readExact(4))
                assertEquals("device", client.readLengthPrefixed())
            }
        }
        upstream.join()
    }

    /**
     * Verifies that the devices list is filtered down to only the leased serial.
     */
    @Test
    fun `host devices response is filtered to leased serial only`() {
        val serial = "test-serial-4"
        val upstream = MockAdbUpstream { conn ->
            assertEquals("host:devices", conn.readCommand())
            conn.writeRaw("OKAY")
            conn.writeLengthPrefixed("$serial\tdevice\nother-serial\tdevice")
        }

        withProxy(upstream, serial) { proxyPort ->
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3_000
                client.sendCommand("host:devices")
                assertEquals("OKAY", client.readExact(4))
                assertEquals("$serial\tdevice", client.readLengthPrefixed())
            }
        }
        upstream.join()
    }

    /**
     * Verifies that a transport command for a foreign serial is rejected.
     */
    @Test
    fun `transport command for foreign serial is rejected with FAIL`() {
        val serial = "test-serial-5"
        val upstream = MockAdbUpstream { _ ->
            // upstream should not be contacted for rejected transports
        }

        withProxy(upstream, serial) { proxyPort ->
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3_000
                client.sendCommand("host:transport:other-serial")
                assertEquals("FAIL", client.readExact(4))
            }
        }
        upstream.join()
    }

    @Test
    fun `releasing a lease tears down connections that are already open`() {
        // Regression guard: stopLease() used to close only the listening socket, so a
        // client that had already connected kept full adb access to the device after its
        // lease ended — while the port was handed back to the pool.
        val upstream = MockAdbUpstream { connection ->
            // Park until the proxy drops us; this connection is never meant to complete.
            runCatching { connection.readCommand() }
        }
        val controller = LeaseScopedAdbProxyController(
            upstream = AdbSocketAddress("127.0.0.1", upstream.port),
            portPool = null
        )
        val device = AdbPhysicalDevice(
            serial = "TEARDOWN1",
            apiLevel = "34",
            manufacturer = "Google",
            model = "Pixel",
            abi = "arm64-v8a"
        )
        val proxyPort = controller.startDeviceProxy("lease-teardown", device)

        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 5_000
                // The connection must be live before the lease is released, otherwise the
                // test would pass even without the fix.
                assertTrue(client.isConnected, "client failed to connect to the proxy")

                controller.stopLease("lease-teardown")

                // Once the proxy closes its side, read() returns -1 (clean EOF) or throws
                // a reset. A SocketTimeoutException means the socket is still OPEN — that
                // is the regression, so it must fail rather than be swallowed as "closed".
                val observed = runCatching { client.getInputStream().read() }
                observed.exceptionOrNull()?.let { error ->
                    if (error is SocketTimeoutException) {
                        throw AssertionError(
                            "in-flight adb connection survived lease release: read() blocked until timeout"
                        )
                    }
                    // Any other IOException is a reset — the connection is gone. Good.
                    return@let
                }
                observed.getOrNull()?.let { byteRead ->
                    assertEquals(
                        -1,
                        byteRead,
                        "expected EOF after lease release, but the proxy served a byte"
                    )
                }
            }
        } finally {
            controller.stopLease("lease-teardown")
            upstream.close()
        }
    }

    @Test
    fun `releasing a lease stops the listener accepting new connections`() {
        val upstream = MockAdbUpstream { }
        val controller = LeaseScopedAdbProxyController(
            upstream = AdbSocketAddress("127.0.0.1", upstream.port),
            portPool = null
        )
        val proxyPort = controller.startDeviceProxy(
            "lease-closed",
            AdbPhysicalDevice(
                serial = "CLOSED01",
                apiLevel = "34",
                manufacturer = "Google",
                model = "Pixel",
                abi = "arm64-v8a"
            )
        )
        controller.stopLease("lease-closed")

        assertFailsWith<java.io.IOException> {
            Socket("127.0.0.1", proxyPort).use { it.getInputStream().read() }
        }
        upstream.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Starts a [LeaseScopedAdbProxyController] pointing at [upstream], allocates
     * an ephemeral port for the leased device [serial], calls [block] with that
     * port, then releases the lease and closes the upstream server.
     */
    private fun withProxy(upstream: MockAdbUpstream, serial: String, block: (proxyPort: Int) -> Unit) {
        val controller = LeaseScopedAdbProxyController(
            upstream = AdbSocketAddress("127.0.0.1", upstream.port),
            portPool = null
        )
        val device = AdbPhysicalDevice(
            serial = serial,
            apiLevel = "34",
            manufacturer = "Google",
            model = "Pixel",
            abi = "arm64-v8a"
        )
        val proxyPort = controller.startDeviceProxy("lease-$serial", device)
        try {
            block(proxyPort)
        } finally {
            controller.stopLease("lease-$serial")
            upstream.close()
        }
    }

    // -------------------------------------------------------------------------
    // Mock upstream
    // -------------------------------------------------------------------------

    /**
     * A minimal ADB server stub that listens on an ephemeral port, accepts one
     * connection, runs [handler] against it, then stops.
     */
    private class MockAdbUpstream(handler: (MockAdbConnection) -> Unit) {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort
        private val thread = thread(isDaemon = true) {
            runCatching {
                serverSocket.accept().use { socket ->
                    handler(MockAdbConnection(socket.getInputStream(), socket.getOutputStream()))
                }
            }
        }

        fun close() = runCatching { serverSocket.close() }
        fun join(timeoutMs: Long = 2_000) = thread.join(timeoutMs)
    }

    private class MockAdbConnection(
        private val input: InputStream,
        private val output: OutputStream
    ) {
        /** Reads one ADB smart-socket command (4-char hex length + payload). */
        fun readCommand(): String {
            val lenStr = readExactBytes(4).decodeToString()
            val len = lenStr.toInt(16)
            return readExactBytes(len).decodeToString()
        }

        /** Writes raw bytes (no length prefix — used for OKAY/FAIL and raw shell output). */
        fun writeRaw(data: String) {
            output.write(data.encodeToByteArray())
            output.flush()
        }

        /** Writes a 4-char-hex-length-prefixed string. */
        fun writeLengthPrefixed(data: String) {
            val bytes = data.encodeToByteArray()
            output.write(bytes.size.toString(16).padStart(4, '0').uppercase().encodeToByteArray())
            output.write(bytes)
            output.flush()
        }

        private fun readExactBytes(n: Int): ByteArray {
            val buf = ByteArray(n)
            var total = 0
            while (total < n) {
                val read = input.read(buf, total, n - total)
                if (read < 0) break
                total += read
            }
            return buf
        }
    }

    // -------------------------------------------------------------------------
    // Socket extension helpers
    // -------------------------------------------------------------------------

    private fun Socket.sendCommand(cmd: String) {
        val out = getOutputStream()
        out.write(cmd.length.toString(16).padStart(4, '0').uppercase().encodeToByteArray())
        out.write(cmd.encodeToByteArray())
        out.flush()
    }

    private fun Socket.readExact(n: Int): String {
        val buf = ByteArray(n)
        var total = 0
        val inp = getInputStream()
        while (total < n) {
            val read = inp.read(buf, total, n - total)
            if (read < 0) break
            total += read
        }
        return buf.decodeToString()
    }

    private fun Socket.readLengthPrefixed(): String {
        val lenStr = readExact(4)
        val len = lenStr.toInt(16)
        return readExact(len)
    }

    private fun Socket.readAll(): String = getInputStream().readBytes().decodeToString()
}
