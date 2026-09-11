package dev.shepherd.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.shepherd.client.ShepherdClient
import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.StatusResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MAX_WAIT_SECONDS: Long = 30

class SessionCreate(clients: ClientFactory) : ShepherdCommand("create", "Create a test session", clients) {
    private val devices: Int? by option("--devices", "-d", help = "Number of devices (default: 1, or one per --device-id)").int()
    private val api: String? by option("--api", help = "API levels: 34, >=33, 33..35 or 33,34 (default: any)")
    private val ttl: Long by option("--ttl", help = "Lifetime in seconds").long().default(3600)
    private val deviceType: String? by option("--device-type", help = "physical or emulator")
    private val deviceIds: List<String> by option("--device-id", help = "Allocate only this device (provider:id); repeatable").multiple()
    private val labels: Map<String, String> by option("--label", help = "Required device label KEY=VALUE; repeatable").associate()
    private val sessionName: String? by option("--name", help = "Display name, e.g. a CI job and build number")
    private val metadata: Map<String, String> by option("--meta", help = "Metadata KEY=VALUE kept with the session; repeatable").associate()
    private val priority: Int by option("--priority", help = "Queue priority under the priority scheduler").int().default(0)
    private val idleTimeout: Long? by option("--idle-timeout", help = "Release the session after this many seconds without a heartbeat")
        .long()
    private val wait: Boolean by option("--wait", help = "Wait until the devices are allocated; Ctrl+C releases the queued session").flag()
    private val waitTimeout: Long by option("--wait-timeout", help = "With --wait, seconds to stay queued before releasing").long()
        .default(900)

    override suspend fun execute(client: ShepherdClient) {
        val request = CreateSessionRequest(
            maxDevices = devices,
            api = api,
            ttlSeconds = ttl,
            deviceType = deviceType,
            deviceIds = deviceIds,
            labels = labels,
            name = sessionName,
            metadata = metadata,
            priority = priority,
            idleTimeoutSeconds = idleTimeout
        )
        val created: SessionResponse = client.createSession(request)
        val session: SessionResponse = if (wait) awaitDevices(client, created) else created
        if (jsonOutput) {
            printJson(SessionResponse.serializer(), session)
        } else {
            echo(formatSession(session))
        }
    }

    private suspend fun awaitDevices(client: ShepherdClient, created: SessionResponse): SessionResponse {
        // Ctrl+C while queued must not leave a session waiting for devices nobody will use.
        val releaseOnExit = Thread { runBlocking { runCatching { client.releaseSession(created.id) } } }
        Runtime.getRuntime().addShutdownHook(releaseOnExit)
        try {
            return client.awaitReady(created, waitTimeout.seconds) { update ->
                if (update.status == ShepherdClient.STATUS_PENDING) {
                    val position: String = update.queuePosition?.let { place -> " at position $place" }.orEmpty()
                    echo("Session ${update.id} is queued$position", err = true)
                }
            }
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(releaseOnExit) }
        }
    }
}

/** A command about one session, named by argument or, as before, with `--id`. */
abstract class SessionCommand(name: String, helpText: String, clients: ClientFactory) : ShepherdCommand(name, helpText, clients) {
    private val idOption: String? by option("--id", help = "Session id")
    private val idArgument: String? by argument("SESSION_ID", help = "Session id (or --id)").optional()

    protected val sessionId: String
        get() = idOption ?: idArgument ?: throw UsageError("Missing session id: pass it as an argument or with --id")

    protected fun printSession(session: SessionResponse) {
        if (jsonOutput) {
            printJson(SessionResponse.serializer(), session)
        } else {
            echo(formatSession(session))
        }
    }
}

class SessionShow(clients: ClientFactory) : SessionCommand("show", "Show a session", clients) {
    override suspend fun execute(client: ShepherdClient) = printSession(client.getSession(sessionId))
}

class SessionRelease(clients: ClientFactory) : SessionCommand("release", "Release a session and its devices", clients) {
    override suspend fun execute(client: ShepherdClient) {
        val id: String = sessionId
        client.releaseSession(id)
        if (jsonOutput) {
            printJson(StatusResponse.serializer(), StatusResponse("released"))
        } else {
            echo("Session $id released.")
        }
    }
}

class SessionList(clients: ClientFactory) : ShepherdCommand("list", "List active sessions", clients) {
    private val status: String? by option("--status", help = "PENDING, READY, RELEASED, EXPIRED or FAILED")
    private val mine: Boolean by option("--mine", help = "Only sessions created with this API key").flag()
    private val owner: String? by option("--owner", help = "Only sessions of this client")

    override suspend fun execute(client: ShepherdClient) {
        val sessions: List<SessionResponse> = client.listSessions(status, if (mine) OWNER_ME else owner)
        if (jsonOutput) {
            printJson(ListSerializer(SessionResponse.serializer()), sessions)
        } else {
            echo(formatSessionTable(sessions))
        }
    }

    private companion object {
        const val OWNER_ME = "me"
    }
}

class SessionWait(clients: ClientFactory) : SessionCommand("wait", "Wait until a queued session gets its devices", clients) {
    private val timeout: Long by option("--timeout", help = "Seconds to wait").long().default(900)

    override suspend fun execute(client: ShepherdClient) {
        val id: String = sessionId
        val started = TimeSource.Monotonic.markNow()
        var session: SessionResponse = client.getSession(id)
        while (session.status == ShepherdClient.STATUS_PENDING) {
            val remaining: Duration = timeout.seconds - started.elapsedNow()
            if (!remaining.isPositive()) {
                break
            }
            session = client.waitForSession(id, remaining.inWholeSeconds.coerceIn(1, MAX_WAIT_SECONDS))
        }
        printSession(session)
        if (session.status != ShepherdClient.STATUS_READY) {
            throw ProgramResult(1)
        }
    }
}

class SessionHeartbeat(clients: ClientFactory) : SessionCommand("heartbeat", "Tell the manager a session is still in use", clients) {
    override suspend fun execute(client: ShepherdClient) {
        val session: SessionResponse = client.heartbeat(sessionId)
        if (jsonOutput) {
            printJson(SessionResponse.serializer(), session)
        } else {
            echo("Session ${session.id} is in use; it expires at ${session.expiresAt}.")
        }
    }
}

class SessionExtend(clients: ClientFactory) : SessionCommand("extend", "Keep a session for longer", clients) {
    private val ttl: Long by option("--ttl", help = "New lifetime in seconds, counted from now").long().required()

    override suspend fun execute(client: ShepherdClient) = printSession(client.extendSession(sessionId, ttl))
}
