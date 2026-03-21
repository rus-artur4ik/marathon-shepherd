package dev.shepherd.adapter.api

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Shared process execution utility for adapters that shell out
 * to local CLI tools (adb, cvdr, etc.).
 */
data class CommandResult(
    val exitCode: Int,
    val output: String,
    val isSuccess: Boolean
)

fun runCommand(
    vararg command: String,
    timeoutSeconds: Long = 30
): CommandResult {
    val logger = LoggerFactory.getLogger("dev.shepherd.adapter.command")
    return try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            logger.error("Command timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
            return CommandResult(exitCode = -1, output = "timeout", isSuccess = false)
        }

        val exitCode = process.exitValue()
        CommandResult(exitCode = exitCode, output = output, isSuccess = exitCode == 0)
    } catch (e: Exception) {
        logger.error("Command failed: ${command.joinToString(" ")}", e)
        CommandResult(exitCode = -1, output = e.message.orEmpty(), isSuccess = false)
    }
}
