package dev.shepherd.adapter.adb

import dev.shepherd.adapter.api.runCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Thin wrapper around the local adb daemon.
 * Queries attached physical devices — lease state is handled separately.
 */
class AdbService(private val adbCommandTimeoutSeconds: Long = 10) {
    private val logger = LoggerFactory.getLogger(AdbService::class.java)

    suspend fun listPhysicalDevices(): List<AdbPhysicalDevice> = withContext(Dispatchers.IO) {
        val result = runCommand("adb", "devices", timeoutSeconds = adbCommandTimeoutSeconds)
        if (!result.isSuccess) {
            logger.warn("adb devices failed (exit ${result.exitCode}): ${result.output}")
            return@withContext emptyList()
        }
        parsePhysicalDeviceSerials(result.output)
            .mapNotNull { serial -> loadPhysicalDevice(serial) }
    }

    suspend fun isAdbReachable(): Boolean = withContext(Dispatchers.IO) {
        val result = runCommand("adb", "devices", timeoutSeconds = adbCommandTimeoutSeconds)
        result.isSuccess && result.output.contains("List of devices")
    }

    private fun parsePhysicalDeviceSerials(adbDevicesOutput: String): List<String> {
        return adbDevicesOutput.lines()
            .filter { it.endsWith("\tdevice") }
            .map { it.split("\t").first().trim() }
            .filter { serial -> !serial.startsWith("emulator-") }
    }

    private suspend fun loadPhysicalDevice(serial: String): AdbPhysicalDevice? {
        val result = runCommand("adb", "-s", serial, "shell", "getprop", timeoutSeconds = adbCommandTimeoutSeconds)
        if (!result.isSuccess) {
            logger.warn("adb getprop failed for $serial (exit ${result.exitCode}): ${result.output}")
            return null
        }
        val properties: Map<String, String> = parseGetpropOutput(result.output)
        return AdbPhysicalDevice(
            serial = serial,
            apiLevel = properties["ro.build.version.sdk"],
            manufacturer = properties["ro.product.manufacturer"],
            model = properties["ro.product.model"],
            abi = properties["ro.product.cpu.abi"]
        )
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
