package dev.shepherd.mcp

import dev.shepherd.protocol.CreateSessionRequest
import dev.shepherd.protocol.DeviceQuery
import dev.shepherd.protocol.SessionResponse
import dev.shepherd.protocol.ShepherdApi
import dev.shepherd.protocol.ShepherdApiException
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

internal const val STATUS_PENDING: String = "PENDING"
internal const val STATUS_READY: String = "READY"

/** How long one server-side long poll may take; the manager caps it at this. */
private const val MAX_SERVER_WAIT_SECONDS: Long = 30
private const val DEFAULT_WAIT_SECONDS: Long = 20
private const val DEFAULT_SESSION_NAME: String = "mcp agent"
private const val MAX_NAME_LENGTH: Int = 200
private const val OWNER_ME: String = "me"

/** Sessions created through MCP carry this metadata, so people can tell them apart from CI jobs. */
internal const val METADATA_CLIENT: String = "client"
internal const val CLIENT_MCP: String = "mcp"

private val EMPTY_ARGUMENTS = JsonObject(emptyMap())

/** The tools an agent uses to find, lease, keep and return devices. */
internal class ShepherdTools(private val api: ShepherdApi, private val guardrails: McpGuardrails) {
    private val defaultWaitSeconds: Long = minOf(DEFAULT_WAIT_SECONDS, guardrails.maxWaitSeconds)

    fun register(server: Server) {
        server.addTool(
            name = "list_devices",
            title = "List devices",
            description = "List the Android devices Marathon Shepherd manages, with state (available, busy, offline, " +
                "maintenance), type, API level, model and labels, and each provider's pool totals. Filters are optional.",
            inputSchema = objectSchema {
                string("state", "Only devices in this state: available, busy, offline or maintenance")
                string("provider", "Only devices of this provider (device host)")
                string("device_type", "physical or emulator")
                string("api", "API levels, e.g. 34, >=33, 33..35 or 33,34")
                labels("labels", "Only devices that carry all of these labels")
            },
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false)
        ) { request -> answer { listDevices(request.arguments ?: EMPTY_ARGUMENTS) } }

        server.addTool(
            name = "acquire_devices",
            title = "Acquire devices",
            description = "Lease Android devices. The answer is a session: when READY it lists adb servers to use " +
                "(adb -H <host> -P <port> devices); when PENDING the devices are busy and the session is queued, so call " +
                "wait_for_session. At most ${guardrails.maxDevicesPerSession} device(s) per session. The session expires after " +
                "its lifetime and is released after ${guardrails.idleTimeoutSeconds}s without get_session; " +
                "call release_session when you are done.",
            inputSchema = objectSchema {
                integer("count", "How many devices (default 1, or one per device id)", 1, guardrails.maxDevicesPerSession.toLong())
                string("api", "API levels, e.g. 34, >=33, 33..35 or 33,34")
                string("device_type", "physical or emulator")
                stringList("device_ids", "Only these devices, by id from list_devices")
                labels("labels", "Every device must carry these labels")
                integer(
                    "ttl_seconds",
                    "Lifetime in seconds (default ${guardrails.defaultTtlSeconds}, at most ${guardrails.maxTtlSeconds})",
                    1,
                    guardrails.maxTtlSeconds
                )
                string("purpose", "What the devices are for; shown to people looking at the fleet")
                integer(
                    "wait_seconds",
                    "How long to wait for busy devices before answering (default $defaultWaitSeconds)",
                    0,
                    guardrails.maxWaitSeconds
                )
            },
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false)
        ) { request -> answer { acquireDevices(request.arguments ?: EMPTY_ARGUMENTS) } }

        server.addTool(
            name = "wait_for_session",
            title = "Wait for a queued session",
            description = "Wait for a PENDING session to get its devices, then return it. Call it again while the session " +
                "stays PENDING: a queued session that nobody waits on for 90 seconds is dropped.",
            inputSchema = objectSchema {
                string("session_id", "The session id", required = true)
                integer("timeout_seconds", "How long to wait (default $defaultWaitSeconds)", 1, maxOf(1, guardrails.maxWaitSeconds))
            },
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false)
        ) { request -> answer { waitForSession(request.arguments ?: EMPTY_ARGUMENTS) } }

        server.addTool(
            name = "get_session",
            title = "Get a session",
            description = "Show a session's state, adb servers and devices. It also tells the manager you are still " +
                "using the session, which keeps it from being released as idle.",
            inputSchema = objectSchema { string("session_id", "The session id", required = true) },
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false)
        ) { request -> answer { getSession(request.arguments ?: EMPTY_ARGUMENTS) } }

        server.addTool(
            name = "list_my_sessions",
            title = "List my sessions",
            description = "List the active sessions of this API key, with its device quota and usage.",
            inputSchema = objectSchema {},
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false)
        ) { answer { listMySessions() } }

        server.addTool(
            name = "extend_session",
            title = "Extend a session",
            description = "Keep a session for ttl_seconds from now (at most ${guardrails.maxTtlSeconds}). " +
                "Not every device provider supports it.",
            inputSchema = objectSchema {
                string("session_id", "The session id", required = true)
                integer("ttl_seconds", "New lifetime in seconds, counted from now", 1, guardrails.maxTtlSeconds, required = true)
            },
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false)
        ) { request -> answer { extendSession(request.arguments ?: EMPTY_ARGUMENTS) } }

        server.addTool(
            name = "release_session",
            title = "Release a session",
            description = "Return a session's devices to the pool. Do this as soon as you no longer need them.",
            inputSchema = objectSchema { string("session_id", "The session id", required = true) },
            toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false)
        ) { request -> answer { releaseSession(request.arguments ?: EMPTY_ARGUMENTS) } }
    }

    private suspend fun listDevices(arguments: JsonObject): String = formatDevices(
        api.listDevices(
            DeviceQuery(
                state = arguments.string("state"),
                provider = arguments.string("provider"),
                deviceType = arguments.string("device_type"),
                api = arguments.string("api"),
                labels = arguments.labels("labels")
            )
        )
    )

    private suspend fun acquireDevices(arguments: JsonObject): String {
        val deviceIds: List<String> = arguments.stringList("device_ids")
        val count: Int = arguments.long("count")?.toInt() ?: deviceIds.size.takeIf { size -> size > 0 } ?: 1
        val limit: Int = guardrails.maxDevicesPerSession
        require(count in 1..limit && deviceIds.size <= limit) {
            "Sessions opened through MCP hold 1 to $limit device(s); ask for fewer"
        }
        val lifetime: Lifetime = lifetime(arguments.long("ttl_seconds") ?: guardrails.defaultTtlSeconds)
        val created: SessionResponse = api.createSession(
            CreateSessionRequest(
                maxDevices = count,
                api = arguments.string("api"),
                ttlSeconds = lifetime.seconds,
                deviceType = arguments.string("device_type"),
                deviceIds = deviceIds,
                labels = arguments.labels("labels"),
                name = arguments.string("purpose")?.take(MAX_NAME_LENGTH) ?: DEFAULT_SESSION_NAME,
                metadata = mapOf(METADATA_CLIENT to CLIENT_MCP),
                idleTimeoutSeconds = guardrails.idleTimeoutSeconds
            )
        )
        val session: SessionResponse = waitWhilePending(created, waitSeconds(arguments.long("wait_seconds")))
        return describeSession(session) + lifetime.note()
    }

    private suspend fun waitForSession(arguments: JsonObject): String {
        val id: String = arguments.requiredString("session_id")
        val current: SessionResponse = api.getSession(id)
        val waited: SessionResponse = waitWhilePending(current, waitSeconds(arguments.long("timeout_seconds")))
        return describeSession(if (waited.status == STATUS_READY) keepAlive(waited) else waited)
    }

    private suspend fun getSession(arguments: JsonObject): String {
        val current: SessionResponse = api.getSession(arguments.requiredString("session_id"))
        return describeSession(if (current.status == STATUS_READY || current.status == STATUS_PENDING) keepAlive(current) else current)
    }

    private suspend fun listMySessions(): String = formatMySessions(api.whoAmI(), api.listSessions(owner = OWNER_ME))

    private suspend fun extendSession(arguments: JsonObject): String {
        val lifetime: Lifetime = lifetime(arguments.long("ttl_seconds") ?: throw IllegalArgumentException("ttl_seconds is required"))
        return describeSession(api.extendSession(arguments.requiredString("session_id"), lifetime.seconds)) + lifetime.note()
    }

    private suspend fun releaseSession(arguments: JsonObject): String {
        val id: String = arguments.requiredString("session_id")
        api.releaseSession(id)
        return "Session $id released; its devices are back in the pool."
    }

    /** Heartbeats [session]; someone else's session is only looked at, not kept alive. */
    private suspend fun keepAlive(session: SessionResponse): SessionResponse = try {
        api.heartbeat(session.id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        session
    }

    private suspend fun waitWhilePending(session: SessionResponse, seconds: Long): SessionResponse {
        var current: SessionResponse = session
        val started = TimeSource.Monotonic.markNow()
        while (current.status == STATUS_PENDING) {
            val remaining: Long = seconds - started.elapsedNow().inWholeSeconds
            if (remaining <= 0) {
                break
            }
            current = api.waitForSession(current.id, remaining.coerceAtMost(MAX_SERVER_WAIT_SECONDS))
        }
        return current
    }

    private fun waitSeconds(requested: Long?): Long = (requested ?: defaultWaitSeconds).coerceIn(0, guardrails.maxWaitSeconds)

    private fun lifetime(requested: Long): Lifetime {
        require(requested > 0) { "ttl_seconds must be positive" }
        return if (requested > guardrails.maxTtlSeconds) {
            Lifetime(
                guardrails.maxTtlSeconds,
                capped = true
            )
        } else {
            Lifetime(requested, capped = false)
        }
    }

    /** A catch-all so that every failure reaches the agent as a readable tool error. */
    private suspend fun answer(block: suspend () -> String): CallToolResult = try {
        CallToolResult(content = listOf(TextContent(text = block())))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: ShepherdApiException) {
        failure("Marathon Shepherd refused the request (HTTP ${refused.status}): ${refused.message}")
    } catch (error: Exception) {
        // Inside the manager the tools call the domain directly, whose exceptions carry user-facing messages.
        failure(error.message ?: error.javaClass.simpleName)
    }

    private fun failure(message: String): CallToolResult = CallToolResult(content = listOf(TextContent(text = message)), isError = true)

    private data class Lifetime(val seconds: Long, val capped: Boolean) {
        fun note(): String = if (capped) "\nThe lifetime was capped at ${seconds}s for sessions opened through MCP." else ""
    }
}

private fun JsonObject.string(name: String): String? = when (val value: JsonElement? = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.content.trim().takeIf { text -> text.isNotEmpty() }
    else -> throw IllegalArgumentException("$name must be a string")
}

private fun JsonObject.requiredString(name: String): String = string(name) ?: throw IllegalArgumentException("$name is required")

/** Whole numbers, also when an agent sends them as "2" or 2.0. */
private fun JsonObject.long(name: String): Long? = when (val value: JsonElement? = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive ->
        value.longOrNull
            ?: value.doubleOrNull?.takeIf { number -> number % 1.0 == 0.0 }?.toLong()
            ?: value.content.trim().toLongOrNull()
            ?: throw IllegalArgumentException("$name must be a whole number")
    else -> throw IllegalArgumentException("$name must be a whole number")
}

private fun JsonObject.stringList(name: String): List<String> = when (val value: JsonElement? = this[name]) {
    null, JsonNull -> emptyList()
    is JsonArray -> value.map { item ->
        (item as? JsonPrimitive)?.takeUnless { primitive -> primitive is JsonNull }?.content
            ?: throw IllegalArgumentException("$name must be a list of strings")
    }
    is JsonPrimitive -> listOf(value.content)
    else -> throw IllegalArgumentException("$name must be a list of strings")
}

private fun JsonObject.labels(name: String): Map<String, String> = when (val value: JsonElement? = this[name]) {
    null, JsonNull -> emptyMap()
    is JsonObject -> value.mapValues { (key, label) ->
        (label as? JsonPrimitive)?.takeUnless { primitive -> primitive is JsonNull }?.content
            ?: throw IllegalArgumentException("label '$key' must be a string")
    }
    else -> throw IllegalArgumentException("$name must be an object of label names to values")
}

/** Builds the JSON Schema of a tool's arguments object. */
private class SchemaBuilder {
    private val properties = LinkedHashMap<String, JsonElement>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false) = add(
        name,
        required,
        buildJsonObject {
            put("type", "string")
            put("description", description)
        }
    )

    fun integer(name: String, description: String, minimum: Long, maximum: Long, required: Boolean = false) = add(
        name,
        required,
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            put("minimum", minimum)
            put("maximum", maximum)
        }
    )

    fun stringList(name: String, description: String) = add(
        name,
        isRequired = false,
        schema = buildJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", description)
        }
    )

    fun labels(name: String, description: String) = add(
        name,
        isRequired = false,
        schema = buildJsonObject {
            put("type", "object")
            putJsonObject("additionalProperties") { put("type", "string") }
            put("description", description)
        }
    )

    private fun add(name: String, isRequired: Boolean, schema: JsonObject) {
        properties[name] = schema
        if (isRequired) {
            required += name
        }
    }

    fun build(): ToolSchema = ToolSchema(properties = JsonObject(properties), required = required.toList())
}

private fun objectSchema(block: SchemaBuilder.() -> Unit): ToolSchema = SchemaBuilder().apply(block).build()
