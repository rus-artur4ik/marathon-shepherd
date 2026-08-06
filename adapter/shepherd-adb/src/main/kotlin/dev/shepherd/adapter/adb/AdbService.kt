package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.CommandResult
import dev.shepherd.adapter.api.runCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

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

    suspend fun listPhysicalDevices(): List<AdbPhysicalDevice> = withContext(Dispatchers.IO) {
        val result = commandRunner(listOf("adb", "devices"), adbCommandTimeoutSeconds)
        if (!result.isSuccess) {
            logger.warn("adb devices failed (exit ${result.exitCode}): ${result.output}")
            return@withContext emptyList()
        }
        parsePhysicalDeviceSerials(result.output)
            .mapNotNull { serial -> loadPhysicalDevice(serial) }
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

    private fun parsePhysicalDeviceSerials(adbDevicesOutput: String): List<String> {
        return adbDevicesOutput.lines()
            .filter { it.endsWith("\tdevice") }
            .map { it.split("\t").first().trim() }
            .filter { serial -> !serial.startsWith("emulator-") }
    }

    private suspend fun loadPhysicalDevice(serial: String): AdbPhysicalDevice? {
        val result = commandRunner(listOf("adb", "-s", serial, "shell", "getprop"), adbCommandTimeoutSeconds)
        if (!result.isSuccess) {
            logger.warn("adb getprop failed for $serial (exit ${result.exitCode}): ${result.output}")
            return null
        }
        val properties: Map<String, String> = parseGetpropOutput(result.output)
        if (!isBootCompleted(properties)) {
            logger.info(
                "Skipping adb device {} until boot completes " +
                    "(sys.boot_completed={}, dev.bootcomplete={}, service.bootanim.exit={}, init.svc.bootanim={})",
                serial,
                properties["sys.boot_completed"],
                properties["dev.bootcomplete"],
                properties["service.bootanim.exit"],
                properties["init.svc.bootanim"]
            )
            return null
        }
        return AdbPhysicalDevice(
            serial = serial,
            apiLevel = properties["ro.build.version.sdk"],
            manufacturer = properties["ro.product.manufacturer"],
            model = properties["ro.product.model"],
            abi = properties["ro.product.cpu.abi"]
        )
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

data class DeviceProfileKey(
    val apiLevel: String?,
    val manufacturer: String?,
    val model: String?,
    val abi: String?
)
