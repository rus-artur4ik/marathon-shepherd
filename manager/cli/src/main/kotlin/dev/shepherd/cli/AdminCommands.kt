package dev.shepherd.cli

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.shepherd.client.ShepherdClient
import dev.shepherd.protocol.AuditPage
import dev.shepherd.protocol.ClientDto
import dev.shepherd.protocol.ClientKeyResponse
import dev.shepherd.protocol.CreateClientRequest
import dev.shepherd.protocol.QuotaDto
import dev.shepherd.protocol.UpdateClientRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

private const val NO_LIMIT = "none"

/** A quota flag's value: a new [limit], or null to lift the limit. */
data class LimitChange(val limit: Long?)

class ClientsCommand(clients: ClientFactory) : ShepherdCommand("clients", "List API clients (admin)", clients) {
    override val invokeWithoutSubcommand: Boolean = true

    private val all: Boolean by option("--all", help = "Include revoked clients").flag()

    override suspend fun execute(client: ShepherdClient) {
        val registered: List<ClientDto> = client.listClients(includeRevoked = all)
        if (jsonOutput) {
            printJson(ListSerializer(ClientDto.serializer()), registered)
        } else {
            echo(formatClients(registered))
        }
    }
}

class ClientCreate(clients: ClientFactory) : ShepherdCommand("create", "Create an API client and print its key once (admin)", clients) {
    private val clientName: String by option("--name", help = "Unique client name").required()
    private val role: String by option("--role", help = "admin, user, viewer or provider").default("user")
    private val description: String? by option("--description")
    private val maxDevices: Int? by option("--max-devices", help = "Devices it may hold at once").int()
    private val maxLifetime: Long? by option("--max-lifetime", help = "Longest session lifetime in seconds").long()
    private val maxPriority: Int? by option("--max-priority", help = "Highest queue priority it may ask for").int()

    override suspend fun execute(client: ShepherdClient) {
        val issued: ClientKeyResponse = client.createClient(
            CreateClientRequest(
                name = clientName,
                role = role,
                description = description,
                quota = QuotaDto(maxDevices = maxDevices, maxSessionLifetimeSeconds = maxLifetime, maxPriority = maxPriority)
            )
        )
        if (jsonOutput) {
            printJson(ClientKeyResponse.serializer(), issued)
        } else {
            echo(formatIssuedKey("Created", issued))
        }
    }
}

class ClientUpdate(clients: ClientFactory) : ShepherdCommand("update", "Change a client's role, description or quota (admin)", clients) {
    private val clientId: String by argument("CLIENT_ID")
    private val role: String? by option("--role", help = "admin, user, viewer or provider")
    private val description: String? by option("--description")
    private val maxDevices: LimitChange? by option("--max-devices", help = "A number, or $NO_LIMIT to lift the limit")
        .convert { raw -> limitChange(raw) ?: fail("expected a number or '$NO_LIMIT'") }
    private val maxLifetime: LimitChange? by option("--max-lifetime", help = "Seconds, or $NO_LIMIT to lift the limit")
        .convert { raw -> limitChange(raw) ?: fail("expected a number or '$NO_LIMIT'") }
    private val maxPriority: LimitChange? by option("--max-priority", help = "A number, or $NO_LIMIT to lift the limit")
        .convert { raw -> limitChange(raw) ?: fail("expected a number or '$NO_LIMIT'") }

    override suspend fun execute(client: ShepherdClient) {
        val devices: LimitChange? = maxDevices
        val lifetime: LimitChange? = maxLifetime
        val priority: LimitChange? = maxPriority
        val quotaChanged: Boolean = devices != null || lifetime != null || priority != null
        if (role == null && description == null && !quotaChanged) {
            throw UsageError("Nothing to change: pass --role, --description, --max-devices, --max-lifetime or --max-priority")
        }
        val quota: QuotaDto? = if (quotaChanged) {
            // The API replaces the whole quota; keep the limits this command was not asked to change.
            val current: QuotaDto = client.getClient(clientId).quota
            QuotaDto(
                maxDevices = if (devices != null) devices.limit?.toInt() else current.maxDevices,
                maxSessionLifetimeSeconds = if (lifetime != null) lifetime.limit else current.maxSessionLifetimeSeconds,
                maxPriority = if (priority != null) priority.limit?.toInt() else current.maxPriority
            )
        } else {
            null
        }
        val updated: ClientDto = client.updateClient(clientId, UpdateClientRequest(role = role, description = description, quota = quota))
        if (jsonOutput) {
            printJson(ClientDto.serializer(), updated)
        } else {
            echo(formatClient(updated))
        }
    }

    private fun limitChange(raw: String): LimitChange? =
        if (raw.equals(NO_LIMIT, ignoreCase = true)) LimitChange(null) else raw.toLongOrNull()?.let(::LimitChange)
}

class ClientRotate(clients: ClientFactory) :
    ShepherdCommand("rotate", "Issue a new key for a client; the old one stops working (admin)", clients) {
    private val clientId: String by argument("CLIENT_ID")

    override suspend fun execute(client: ShepherdClient) {
        val issued: ClientKeyResponse = client.rotateClientKey(clientId)
        if (jsonOutput) {
            printJson(ClientKeyResponse.serializer(), issued)
        } else {
            echo(formatIssuedKey("Rotated the key of", issued))
        }
    }
}

class ClientRevoke(clients: ClientFactory) : ShepherdCommand("revoke", "Revoke a client's key (admin)", clients) {
    private val clientId: String by argument("CLIENT_ID")
    private val releaseSessions: Boolean by option("--release-sessions", help = "Also release the client's sessions").flag()

    override suspend fun execute(client: ShepherdClient) {
        val revoked: ClientDto = client.revokeClient(clientId, releaseSessions)
        if (jsonOutput) {
            printJson(ClientDto.serializer(), revoked)
        } else {
            echo("Revoked client '${revoked.name}' (${revoked.id}).")
        }
    }
}

class AuditCommand(clients: ClientFactory) :
    ShepherdCommand("audit", "Show the audit log; admins see every client, others their own entries", clients) {
    private val action: String? by option("--action", help = "Action prefix, e.g. session. or client.revoke")
    private val actor: String? by option("--actor", help = "Client name (admins only)")
    private val target: String? by option("--target", help = "Session, client, device or provider id")
    private val before: Long? by option("--before", help = "Only entries older than this id, for paging").long()
    private val limit: Int? by option("--limit", help = "Entries per page").int()

    override suspend fun execute(client: ShepherdClient) {
        val page: AuditPage = client.audit(action = action, actor = actor, target = target, before = before, limit = limit)
        if (jsonOutput) {
            printJson(AuditPage.serializer(), page)
        } else {
            echo(formatAudit(page))
        }
    }
}

class ConfigCommand(clients: ClientFactory) : ShepherdCommand(
    "config",
    "Print the active configuration, secrets redacted (admin)",
    clients
) {
    override val invokeWithoutSubcommand: Boolean = true

    override suspend fun execute(client: ShepherdClient) {
        printJson(JsonObject.serializer(), client.config())
    }
}

class ConfigReload(clients: ClientFactory) : ShepherdCommand("reload", "Reload msh.yaml from disk (admin)", clients) {
    override suspend fun execute(client: ShepherdClient) {
        val config: JsonObject = client.reloadConfig()
        if (jsonOutput) {
            printJson(JsonObject.serializer(), config)
        } else {
            echo("Configuration reloaded.")
        }
    }
}
