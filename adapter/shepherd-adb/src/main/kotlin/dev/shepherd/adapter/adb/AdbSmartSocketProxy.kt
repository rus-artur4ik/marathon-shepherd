package dev.shepherd.adapter.adb

import org.slf4j.LoggerFactory
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class AdbSocketAddress(
    val host: String,
    val port: Int
) {
    companion object {
        fun fromEnvironment(defaultPort: Int = 5037): AdbSocketAddress {
            val socket: String = System.getenv("ADB_SERVER_SOCKET").orEmpty().trim()
            if (socket.isBlank()) {
                return AdbSocketAddress(host = "127.0.0.1", port = defaultPort)
            }
            val tcpPrefix = "tcp:"
            if (!socket.startsWith(tcpPrefix)) {
                return AdbSocketAddress(host = "127.0.0.1", port = defaultPort)
            }
            val target: String = socket.removePrefix(tcpPrefix)
            val host: String = target.substringBefore(':', missingDelimiterValue = "127.0.0.1")
            val port: Int = target.substringAfter(':', missingDelimiterValue = defaultPort.toString()).toIntOrNull()
                ?: defaultPort
            return AdbSocketAddress(host = host, port = port)
        }
    }
}

interface AdbProxyController {
    fun startDeviceProxy(leaseId: String, device: AdbPhysicalDevice, preferredPort: Int? = null): Int
    fun stopLease(leaseId: String)
}

data class AdbProxyPortRange(
    val startPort: Int,
    val endPort: Int
) {
    init {
        require(startPort in 1..65535) { "Proxy port range start must be between 1 and 65535" }
        require(endPort in 1..65535) { "Proxy port range end must be between 1 and 65535" }
        require(startPort <= endPort) { "Proxy port range start must be <= end" }
    }

    companion object {
        fun fromEnvironment(): AdbProxyPortRange? {
            val rawValue: String = System.getenv("ADB_PROXY_PORT_RANGE").orEmpty().trim()
            if (rawValue.isBlank()) {
                return null
            }
            val startPort: Int = rawValue.substringBefore('-', missingDelimiterValue = "").toIntOrNull()
                ?: error("Invalid ADB_PROXY_PORT_RANGE start port: $rawValue")
            val endPort: Int = rawValue.substringAfter('-', missingDelimiterValue = "").toIntOrNull()
                ?: error("Invalid ADB_PROXY_PORT_RANGE end port: $rawValue")
            return AdbProxyPortRange(startPort = startPort, endPort = endPort)
        }
    }
}

class AdbProxyPortPool(private val range: AdbProxyPortRange) {
    private val usedPorts = mutableSetOf<Int>()

    @Synchronized
    fun acquirePort(preferredPort: Int? = null): Int {
        if (preferredPort != null) {
            if (preferredPort !in range.startPort..range.endPort) {
                error("Requested proxy port $preferredPort is outside configured range ${range.startPort}-${range.endPort}")
            }
            if (preferredPort in usedPorts) {
                error("Requested proxy port $preferredPort is already in use")
            }
            usedPorts += preferredPort
            return preferredPort
        }
        val nextPort: Int = (range.startPort..range.endPort).firstOrNull { port -> port !in usedPorts }
            ?: error("No free ports left in ADB proxy range ${range.startPort}-${range.endPort}")
        usedPorts += nextPort
        return nextPort
    }

    @Synchronized
    fun releasePort(port: Int) {
        usedPorts.remove(port)
    }
}

class LeaseScopedAdbProxyController(
    private val upstream: AdbSocketAddress,
    private val portPool: AdbProxyPortPool? = null
) : AdbProxyController {
    private val logger = LoggerFactory.getLogger(LeaseScopedAdbProxyController::class.java)
    private val handlesByLease = ConcurrentHashMap<String, MutableList<LeaseProxyHandle>>()

    override fun startDeviceProxy(leaseId: String, device: AdbPhysicalDevice, preferredPort: Int?): Int {
        val targetPort: Int = when {
            portPool != null -> portPool.acquirePort(preferredPort)
            preferredPort != null -> preferredPort
            else -> 0
        }
        val serverSocket = try {
            ServerSocket().also { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(targetPort))
            }
        } catch (error: Exception) {
            if (targetPort != 0) {
                portPool?.releasePort(targetPort)
            }
            throw error
        }
        val handle = LeaseProxyHandle(
            serial = device.serial,
            port = serverSocket.localPort,
            serverSocket = serverSocket
        )
        handlesByLease.computeIfAbsent(leaseId) { mutableListOf() }.add(handle)
        handle.acceptThread = thread(
            start = true,
            isDaemon = true,
            name = "adb-proxy-$leaseId-${device.serial.takeLast(6)}"
        ) {
            acceptLoop(handle)
        }
        logger.info("Started lease-scoped adb proxy for lease=$leaseId serial=${device.serial} on port=${handle.port}")
        return handle.port
    }

    override fun stopLease(leaseId: String) {
        val handles: List<LeaseProxyHandle> = handlesByLease.remove(leaseId)?.toList().orEmpty()
        handles.forEach { handle ->
            runCatching { handle.serverSocket.close() }
                .onFailure { error -> logger.warn("Failed to close adb proxy ${handle.port}: ${error.message}") }
            portPool?.releasePort(handle.port)
        }
    }

    private fun acceptLoop(handle: LeaseProxyHandle) {
        while (!handle.serverSocket.isClosed) {
            val clientSocket = try {
                handle.serverSocket.accept()
            } catch (_: SocketException) {
                break
            }
            thread(
                start = true,
                isDaemon = true,
                name = "adb-proxy-client-${handle.port}"
            ) {
                clientSocket.use { client ->
                    AdbProxySession(
                        upstream = upstream,
                        allowedSerial = handle.serial
                    ).handle(client)
                }
            }
        }
    }
}

private data class LeaseProxyHandle(
    val serial: String,
    val port: Int,
    val serverSocket: ServerSocket,
    var acceptThread: Thread? = null
)

private class AdbProxySession(
    private val upstream: AdbSocketAddress,
    private val allowedSerial: String
) {
    fun handle(clientSocket: Socket) {
        val clientInput = clientSocket.getInputStream().buffered()
        val clientOutput = clientSocket.getOutputStream().buffered()
        while (!clientSocket.isClosed) {
            val command: String = try {
                readSmartSocketCommand(clientInput) ?: return
            } catch (_: EOFException) {
                return
            }
            when {
                isDevicesCommand(command) -> {
                    respondWithFilteredDevices(command, clientOutput)
                    clientOutput.flush()
                    return
                }
                isTrackDevicesCommand(command) -> {
                    bridgeTrackDevices(command, clientOutput)
                    clientOutput.flush()
                    return
                }
                isTransportCommand(command) -> {
                    bridgeTransportCommand(command, clientSocket, clientInput, clientOutput)
                    clientOutput.flush()
                    return
                }
                isHostSerialCommand(command) -> {
                    bridgeHostSerialCommand(command, clientOutput)
                    clientOutput.flush()
                    return
                }
                isAllowedPassthroughCommand(command) -> {
                    bridgeOneShotHostCommand(command, clientOutput)
                    clientOutput.flush()
                    return
                }
                else -> {
                    writeFail(clientOutput, "Unsupported adb command for lease-scoped proxy: $command")
                    clientOutput.flush()
                    return
                }
            }
        }
    }

    private fun respondWithFilteredDevices(command: String, clientOutput: OutputStream) {
        val upstreamPayload: String = runOneShotLengthPrefixedCommand(command) ?: return writeFail(
            clientOutput,
            "Failed to query adb device inventory"
        )
        writeOkay(clientOutput)
        writeLengthPrefixed(clientOutput, filterDevicesPayload(upstreamPayload, allowedSerial))
    }

    private fun bridgeTrackDevices(command: String, clientOutput: OutputStream) {
        Socket(upstream.host, upstream.port).use { socket ->
            val upstreamInput = socket.getInputStream().buffered()
            val upstreamOutput = socket.getOutputStream().buffered()
            writeSmartSocketCommand(upstreamOutput, command)
            upstreamOutput.flush()
            val status: ByteArray = readExactly(upstreamInput, 4) ?: return writeFail(clientOutput, "ADB upstream closed")
            clientOutput.write(status)
            if (!status.contentEquals(OKAY_BYTES)) {
                forwardLengthPrefixedPayload(upstreamInput, clientOutput)
                return
            }
            while (true) {
                val payload: String = readLengthPrefixedString(upstreamInput) ?: break
                writeLengthPrefixed(clientOutput, filterDevicesPayload(payload, allowedSerial))
                clientOutput.flush()
            }
        }
    }

    private fun bridgeTransportCommand(
        command: String,
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream
    ) {
        val rewrittenCommand: String = rewriteTransportCommand(command, allowedSerial) ?: return writeFail(
            clientOutput,
            "Requested adb transport is outside of this lease"
        )
        Socket(upstream.host, upstream.port).use { socket ->
            val upstreamInput = socket.getInputStream().buffered()
            val upstreamOutput = socket.getOutputStream().buffered()
            writeSmartSocketCommand(upstreamOutput, rewrittenCommand)
            upstreamOutput.flush()
            val status: ByteArray = readExactly(upstreamInput, 4) ?: return writeFail(clientOutput, "ADB upstream closed")
            clientOutput.write(status)
            if (!status.contentEquals(OKAY_BYTES)) {
                forwardLengthPrefixedPayload(upstreamInput, clientOutput)
                return
            }
            pipeBidirectional(clientSocket, clientInput, clientOutput, socket, upstreamInput, upstreamOutput)
        }
    }

    private fun bridgeHostSerialCommand(command: String, clientOutput: OutputStream) {
        val validatedCommand: String = validateHostSerialCommand(command, allowedSerial) ?: return writeFail(
            clientOutput,
            "Requested adb host-serial command is outside of this lease"
        )
        if (isStreamingHostSerialCommand(validatedCommand)) {
            Socket(upstream.host, upstream.port).use { socket ->
                val upstreamInput = socket.getInputStream().buffered()
                val upstreamOutput = socket.getOutputStream().buffered()
                writeSmartSocketCommand(upstreamOutput, validatedCommand)
                upstreamOutput.flush()
                val status: ByteArray = readExactly(upstreamInput, 4) ?: return writeFail(clientOutput, "ADB upstream closed")
                clientOutput.write(status)
                if (!status.contentEquals(OKAY_BYTES)) {
                    forwardLengthPrefixedPayload(upstreamInput, clientOutput)
                    return
                }
                upstreamInput.copyTo(clientOutput)
                clientOutput.flush()
            }
            return
        }
        bridgeOneShotHostCommand(validatedCommand, clientOutput)
    }

    private fun bridgeOneShotHostCommand(command: String, clientOutput: OutputStream) {
        Socket(upstream.host, upstream.port).use { socket ->
            val upstreamInput = socket.getInputStream().buffered()
            val upstreamOutput = socket.getOutputStream().buffered()
            writeSmartSocketCommand(upstreamOutput, command)
            upstreamOutput.flush()
            val status: ByteArray = readExactly(upstreamInput, 4) ?: return writeFail(clientOutput, "ADB upstream closed")
            clientOutput.write(status)
            if (expectsLengthPrefixedPayload(command) || !status.contentEquals(OKAY_BYTES)) {
                forwardLengthPrefixedPayload(upstreamInput, clientOutput)
            }
        }
    }

    private fun runOneShotLengthPrefixedCommand(command: String): String? {
        Socket(upstream.host, upstream.port).use { socket ->
            val upstreamInput = socket.getInputStream().buffered()
            val upstreamOutput = socket.getOutputStream().buffered()
            writeSmartSocketCommand(upstreamOutput, command)
            upstreamOutput.flush()
            val status: ByteArray = readExactly(upstreamInput, 4) ?: return null
            if (!status.contentEquals(OKAY_BYTES)) {
                return null
            }
            return readLengthPrefixedString(upstreamInput)
        }
    }

    private fun pipeBidirectional(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstreamSocket: Socket,
        upstreamInput: InputStream,
        upstreamOutput: OutputStream
    ) {
        val upstreamToClient = thread(start = true, isDaemon = true, name = "adb-proxy-upstream-to-client") {
            runCatching {
                upstreamInput.copyTo(clientOutput)
                clientOutput.flush()
            }
        }
        runCatching {
            clientInput.copyTo(upstreamOutput)
            upstreamOutput.flush()
        }
        runCatching { upstreamSocket.close() }
        runCatching { clientSocket.close() }
        runCatching { upstreamToClient.join(1_000) }
    }
}

private val OKAY_BYTES: ByteArray = "OKAY".encodeToByteArray()
private val FAIL_BYTES: ByteArray = "FAIL".encodeToByteArray()
private val TRACK_DEVICE_COMMANDS: Set<String> = setOf("host:track-devices", "host:track-devices-l")
private val LENGTH_PREFIXED_COMMAND_PREFIXES: List<String> = listOf(
    "host:version",
    "host:host-features",
    "host:features",
    "host:devices",
    "host:devices-l",
    "host:list-forward",
    "host:get-serialno",
    "host:get-devpath",
    "host:get-state",
    "host-serial:"
)
private val ALLOWED_PASSTHROUGH_COMMANDS: Set<String> = setOf(
    "host:version",
    "host:host-features",
    "host:features"
)

internal fun isDevicesCommand(command: String): Boolean = command == "host:devices" || command == "host:devices-l"

internal fun isTrackDevicesCommand(command: String): Boolean = command in TRACK_DEVICE_COMMANDS

internal fun isTransportCommand(command: String): Boolean {
    return command.startsWith("host:transport:") ||
        command.startsWith("host:tport:serial:") ||
        command == "host:transport-any" ||
        command == "host:transport-usb"
}

internal fun isHostSerialCommand(command: String): Boolean = command.startsWith("host-serial:")

internal fun rewriteTransportCommand(command: String, allowedSerial: String): String? {
    return when {
        command == "host:transport-any" || command == "host:transport-usb" -> "host:transport:$allowedSerial"
        command.startsWith("host:transport:") -> {
            val requestedSerial: String = command.removePrefix("host:transport:")
            if (requestedSerial == allowedSerial) command else null
        }
        command.startsWith("host:tport:serial:") -> {
            val requestedSerial: String = command.removePrefix("host:tport:serial:")
            if (requestedSerial == allowedSerial) command else null
        }
        else -> null
    }
}

internal fun validateHostSerialCommand(command: String, allowedSerial: String): String? {
    val prefix = "host-serial:"
    if (!command.startsWith(prefix)) {
        return null
    }
    val remainder: String = command.removePrefix(prefix)
    val serial: String = remainder.substringBefore(':', missingDelimiterValue = "")
    return if (serial == allowedSerial) command else null
}

internal fun isStreamingHostSerialCommand(command: String): Boolean {
    return command.contains(":shell:") ||
        command.contains(":exec:") ||
        command.contains(":sync:") ||
        command.contains(":track-jdwp")
}

internal fun isAllowedPassthroughCommand(command: String): Boolean = command in ALLOWED_PASSTHROUGH_COMMANDS

internal fun expectsLengthPrefixedPayload(command: String): Boolean {
    return LENGTH_PREFIXED_COMMAND_PREFIXES.any { prefix -> command.startsWith(prefix) } &&
        !isStreamingHostSerialCommand(command)
}

internal fun filterDevicesPayload(payload: String, allowedSerial: String): String {
    return payload.lines()
        .filter { line ->
            line.isBlank() || line.substringBefore('\t').substringBefore(' ').trim() == allowedSerial
        }
        .joinToString("\n")
        .trimEnd()
}

private fun readSmartSocketCommand(input: InputStream): String? {
    val lengthPrefix: String = readAscii(input, 4) ?: return null
    val payloadLength: Int = lengthPrefix.toInt(16)
    return readAscii(input, payloadLength)
}

private fun writeSmartSocketCommand(output: OutputStream, command: String) {
    output.write(command.length.toString(16).padStart(4, '0').uppercase().encodeToByteArray())
    output.write(command.encodeToByteArray())
}

private fun readLengthPrefixedString(input: InputStream): String? {
    val lengthPrefix: String = readAscii(input, 4) ?: return null
    val payloadLength: Int = lengthPrefix.toInt(16)
    return readAscii(input, payloadLength)
}

private fun forwardLengthPrefixedPayload(input: InputStream, output: OutputStream) {
    val lengthPrefix: ByteArray = readExactly(input, 4) ?: return
    output.write(lengthPrefix)
    val payloadLength: Int = lengthPrefix.decodeToString().toInt(16)
    if (payloadLength <= 0) {
        return
    }
    val payload: ByteArray = readExactly(input, payloadLength) ?: return
    output.write(payload)
}

private fun writeOkay(output: OutputStream) {
    output.write(OKAY_BYTES)
}

private fun writeFail(output: OutputStream, message: String) {
    output.write(FAIL_BYTES)
    writeLengthPrefixed(output, message)
}

private fun writeLengthPrefixed(output: OutputStream, payload: String) {
    val bytes: ByteArray = payload.encodeToByteArray()
    output.write(bytes.size.toString(16).padStart(4, '0').uppercase().encodeToByteArray())
    output.write(bytes)
}

private fun readAscii(input: InputStream, size: Int): String? {
    return readExactly(input, size)?.decodeToString()
}

private fun readExactly(input: InputStream, size: Int): ByteArray? {
    val buffer = ByteArray(size)
    var totalRead = 0
    while (totalRead < size) {
        val bytesRead = input.read(buffer, totalRead, size - totalRead)
        if (bytesRead < 0) {
            return if (totalRead == 0) null else throw EOFException("Unexpected EOF")
        }
        totalRead += bytesRead
    }
    return buffer
}
