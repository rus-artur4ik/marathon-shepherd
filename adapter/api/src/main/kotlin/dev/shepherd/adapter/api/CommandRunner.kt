package dev.shepherd.adapter.api

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shared process execution utility for adapters that shell out
 * to local CLI tools when an adapter needs them (for example adb).
 */
data class CommandResult(
    val exitCode: Int,
    val output: String,
    val isSuccess: Boolean
)

/** Grace period given to the output-draining thread after the process is killed. */
private const val DRAIN_JOIN_MILLIS = 1_000L

/**
 * Runs [command] and returns its combined stdout/stderr, killing it after [timeoutSeconds].
 *
 * The output is drained on a separate thread. Reading it inline would defeat the timeout
 * entirely: the read only returns at EOF, so a child that hangs while holding its stdout
 * open — exactly what a wedged `adb` does — would block here forever and `waitFor` would
 * never be reached.
 */
fun runCommand(vararg command: String, timeoutSeconds: Long = 30): CommandResult {
    val logger = LoggerFactory.getLogger("dev.shepherd.adapter.command")
    return try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        // AtomicReference rather than a plain var: the value is published from the drain
        // thread and read here after join(), and a local cannot carry @Volatile.
        val output = AtomicReference("")
        val drain = thread(name = "command-output-${process.pid()}", isDaemon = true) {
            output.set(runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault(""))
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            // Killing the process closes its stdout, which releases the reader.
            drain.join(DRAIN_JOIN_MILLIS)
            logger.error("Command timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
            return CommandResult(exitCode = -1, output = "timeout", isSuccess = false)
        }

        drain.join(DRAIN_JOIN_MILLIS)
        val exitCode = process.exitValue()
        CommandResult(exitCode = exitCode, output = output.get(), isSuccess = exitCode == 0)
    } catch (e: Exception) {
        logger.error("Command failed: ${command.joinToString(" ")}", e)
        CommandResult(exitCode = -1, output = e.message.orEmpty(), isSuccess = false)
    }
}
