package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.CommandResult
import dev.shepherd.adapter.api.runCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** [AdbUnavailableDevice.reason] of a device that is attached but has not finished booting. */
const val ADB_REASON_BOOTING: String = "booting"

/** [AdbUnavailableDevice.reason] of a device whose properties could not be read. */
const val ADB_REASON_GETPROP_FAILED: String = "getprop-failed"

/** The `adb devices` state of a device adb can talk to. */
private const val ADB_STATE_DEVICE: String = "device"

/**
 * Thin wrapper around the local adb daemon.
 * Queries attached physical devices — lease state is handled separately.
 */
class AdbService(
    private val adbCommandTimeoutSeconds: Long = 10,
    private val upstream: AdbSocketAddress = AdbSocketAddress.fromEnvironment(),
    private val commandRunner: (List<String>, Long) -> CommandResult = { command, timeoutSeconds ->
        runCommand(*command.toTypedArray(), timeoutSeconds = timeoutSeconds)
    }
) {
    private val logger = LoggerFactory.getLogger(AdbService::class.java)

    /** Devices that can be leased right now: attached, authorized and booted. */
    suspend fun listPhysicalDevices(): List<AdbPhysicalDevice> = listDeviceInventory().readyDevices

    /**
     * Every attached physical device from a single `adb devices` call, split into the ones that
     * can be leased and the ones that cannot yet, with the reason — so `/status` can show an
     * operator an unauthorized or still-booting phone instead of silently leaving it out.
     */
    suspend fun listDeviceInventory(): AdbDeviceInventory = withContext(Dispatchers.IO) {
        val result = commandRunner(listOf("adb", "devices"), adbCommandTimeoutSeconds)
        if (!result.isSuccess) {
            logger.warn("adb devices failed (exit ${result.exitCode}): ${result.output}")
            return@withContext AdbDeviceInventory()
        }
        val readyDevices = mutableListOf<AdbPhysicalDevice>()
        val unavailableDevices = mutableListOf<AdbUnavailableDevice>()
        parseAttachedDevices(result.output).forEach { (serial, state) ->
            if (state != ADB_STATE_DEVICE) {
                unavailableDevices += AdbUnavailableDevice(serial = serial, reason = state)
                return@forEach
            }
            val properties: Map<String, String> = readProperties(serial) ?: run {
                unavailableDevices += AdbUnavailableDevice(serial = serial, reason = ADB_REASON_GETPROP_FAILED)
                return@forEach
            }
            val device = AdbPhysicalDevice(
                serial = serial,
                apiLevel = properties["ro.build.version.sdk"],
                manufacturer = properties["ro.product.manufacturer"],
                model = properties["ro.product.model"],
                abi = properties["ro.product.cpu.abi"]
            )
            if (isBootCompleted(properties)) {
                readyDevices += device
                return@forEach
            }
            logger.info(
                "Skipping adb device {} until boot completes " +
                    "(sys.boot_completed={}, dev.bootcomplete={}, service.bootanim.exit={}, init.svc.bootanim={})",
                serial,
                properties["sys.boot_completed"],
                properties["dev.bootcomplete"],
                properties["service.bootanim.exit"],
                properties["init.svc.bootanim"]
            )
            unavailableDevices += AdbUnavailableDevice(
                serial = serial,
                reason = ADB_REASON_BOOTING,
                apiLevel = device.apiLevel,
                manufacturer = device.manufacturer,
                model = device.model,
                abi = device.abi
            )
        }
        AdbDeviceInventory(readyDevices = readyDevices, unavailableDevices = unavailableDevices)
    }

    suspend fun isAdbReachable(): Boolean = withContext(Dispatchers.IO) {
        val result = commandRunner(listOf("adb", "devices"), adbCommandTimeoutSeconds)
        result.isSuccess && result.output.contains("List of devices")
    }

    suspend fun reloadServer() = withContext(Dispatchers.IO) {
        val killResult = commandRunner(listOf("adb", "kill-server"), adbCommandTimeoutSeconds)
        if (!killResult.isSuccess) {
            logger.warn("adb kill-server failed (exit ${killResult.exitCode}): ${killResult.output}")
        }

        if (upstream.host == "127.0.0.1" || upstream.host == "localhost") {
            val startResult = commandRunner(listOf("adb", "start-server"), adbCommandTimeoutSeconds)
            if (!startResult.isSuccess) {
                logger.warn("adb start-server failed (exit ${startResult.exitCode}): ${startResult.output}")
            }
            return@withContext startResult
        }

        repeat(adbCommandTimeoutSeconds.toInt().coerceAtLeast(1)) { attempt ->
            val statusResult = commandRunner(listOf("adb", "devices"), adbCommandTimeoutSeconds)
            if (statusResult.isSuccess && statusResult.output.contains("List of devices")) {
                return@withContext statusResult
            }
            logger.info(
                "Waiting for remote adb server {}:{} to come back after reload attempt {}/{}",
                upstream.host,
                upstream.port,
                attempt + 1,
                adbCommandTimeoutSeconds
            )
            delay(1000)
        }

        CommandResult(
            exitCode = -1,
            output = "remote adb server ${upstream.host}:${upstream.port} did not become reachable after reload",
            isSuccess = false
        )
    }

    /**
     * Serial and state of each attached physical device. The state is everything after the tab,
     * because some states contain spaces (`no permissions (...)`); lines without a tab are the
     * header or daemon chatter. Emulators are left out: this adapter serves physical devices only.
     */
    private fun parseAttachedDevices(adbDevicesOutput: String): List<Pair<String, String>> {
        return adbDevicesOutput.lines()
            .mapNotNull { line ->
                val serial: String = line.substringBefore('\t', missingDelimiterValue = "").trim()
                val state: String = line.substringAfter('\t', missingDelimiterValue = "").trim()
                if (serial.isEmpty() || state.isEmpty()) null else serial to state
            }
            .filterNot { (serial, _) -> serial.startsWith("emulator-") }
    }

    private fun readProperties(serial: String): Map<String, String>? {
        val result = commandRunner(listOf("adb", "-s", serial, "shell", "getprop"), adbCommandTimeoutSeconds)
        if (!result.isSuccess) {
            logger.warn("adb getprop failed for $serial (exit ${result.exitCode}): ${result.output}")
            return null
        }
        return parseGetpropOutput(result.output)
    }

    private fun isBootCompleted(properties: Map<String, String>): Boolean {
        val completionSignals = listOf(
            properties["sys.boot_completed"],
            properties["dev.bootcomplete"],
            properties["service.bootanim.exit"]
        )
        if (completionSignals.any { value -> value == "1" }) {
            return true
        }
        if (properties["init.svc.bootanim"]?.equals("stopped", ignoreCase = true) == true) {
            return true
        }
        val hasKnownBootSignal = listOf(
            "sys.boot_completed",
            "dev.bootcomplete",
            "service.bootanim.exit",
            "init.svc.bootanim"
        ).any(properties::containsKey)
        return !hasKnownBootSignal
    }

    private fun parseGetpropOutput(output: String): Map<String, String> {
        return output.lineSequence()
            .mapNotNull { line ->
                val key = line.substringAfter("[", missingDelimiterValue = "")
                    .substringBefore("]:", missingDelimiterValue = "")
                    .trim()
                val value = line.substringAfterLast("[", missingDelimiterValue = "")
                    .substringBeforeLast("]", missingDelimiterValue = "")
                    .trim()
                if (key.isBlank()) {
                    null
                } else {
                    key to value
                }
            }
            .toMap()
    }
}

data class AdbPhysicalDevice(
    val serial: String,
    val apiLevel: String?,
    val manufacturer: String?,
    val model: String?,
    val abi: String?
)

/**
 * Every attached physical device, split by whether it can be leased now. Both lists come from
 * the same `adb devices` call, so together they describe one moment.
 */
data class AdbDeviceInventory(
    val readyDevices: List<AdbPhysicalDevice> = emptyList(),
    val unavailableDevices: List<AdbUnavailableDevice> = emptyList()
)

/**
 * An attached device that cannot be leased yet. [reason] is the adb state for anything other
 * than `device` (`unauthorized`, `offline`, `recovery`, ...), [ADB_REASON_BOOTING] or
 * [ADB_REASON_GETPROP_FAILED]; the properties are known only when getprop worked.
 */
data class AdbUnavailableDevice(
    val serial: String,
    val reason: String,
    val apiLevel: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val abi: String? = null
)

data class DeviceProfileKey(
    val apiLevel: String?,
    val manufacturer: String?,
    val model: String?,
    val abi: String?
)
