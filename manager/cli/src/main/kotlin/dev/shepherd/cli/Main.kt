package dev.shepherd.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import dev.shepherd.client.ShepherdClient
import dev.shepherd.common.BuildInfo
import dev.shepherd.protocol.ShepherdApiException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

internal const val DEFAULT_MANAGER_URL = "http://localhost:6037"

/** How mshctl prints JSON: indented, with every field except unset optional ones. */
internal val CliJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

/** One JSON document per line, for streams. */
internal val CliJsonLines = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** Opens the client a command talks to; tests hand in one backed by a mock engine. */
fun interface ClientFactory {
    fun create(managerUrl: String, token: String?): ShepherdClient
}

class MshCtl : CliktCommand(name = "mshctl") {
    init {
        versionOption(BuildInfo.version)
    }

    override fun help(context: Context): String = "Marathon Shepherd CLI: sessions, devices, providers, events, people and API clients. " +
        "Set MSH_URL, then run mshctl login or set MSH_TOKEN (or pass --manager and --token to each command)."

    override fun run() = Unit
}

/** The complete command tree, talking to managers through [clients]. */
fun mshctl(clients: ClientFactory = ClientFactory { managerUrl, token -> ShepherdClient(managerUrl, token) }): CliktCommand =
    MshCtl().subcommands(
        SessionCreate(clients),
        SessionShow(clients),
        SessionList(clients),
        SessionWait(clients),
        SessionHeartbeat(clients),
        SessionExtend(clients),
        SessionRelease(clients),
        DevicesCommand(clients).subcommands(DeviceShow(clients), DeviceMaintenance(clients)),
        ProvidersCommand(clients).subcommands(ProviderRemove(clients)),
        EventsCommand(clients),
        HealthCommand(clients),
        WhoAmICommand(clients),
        ClientsCommand(clients).subcommands(ClientCreate(clients), ClientUpdate(clients), ClientRotate(clients), ClientRevoke(clients)),
        AuditCommand(clients),
        ConfigCommand(clients).subcommands(ConfigReload(clients)),
        LoginCommand(clients),
        LogoutCommand(clients),
        PasswordCommand(clients),
        TokensCommand(clients).subcommands(TokenCreate(clients), TokenRevoke(clients)),
        UsersCommand(clients).subcommands(UserCreate(clients), UserUpdate(clients), UserResetPassword(clients), UserDisable(clients))
    )

fun main(args: Array<String>) = mshctl().main(args)

/**
 * A command that calls the manager. Every command accepts the connection options, so
 * `mshctl create --manager URL` works as it always did; MSH_URL and MSH_TOKEN supply defaults.
 */
abstract class ShepherdCommand(
    name: String,
    private val helpText: String,
    private val clients: ClientFactory
) : CliktCommand(name = name) {
    protected val managerUrl: String by option(
        "--manager",
        "-s",
        envvar = "MSH_URL",
        help = "Manager URL (default: \$MSH_URL or $DEFAULT_MANAGER_URL)"
    ).default(DEFAULT_MANAGER_URL)
    private val token: String? by option(
        "--token",
        envvar = "MSH_TOKEN",
        help = "API key (default: \$MSH_TOKEN, then the token saved by mshctl login)"
    )
    private val credentialsPath: String? by option(
        "--credentials-file",
        envvar = "MSH_CREDENTIALS_FILE",
        help = "Where mshctl login keeps its token"
    )
    protected val credentials: CredentialsFile by lazy {
        CredentialsFile(credentialsPath?.let { path -> File(path) } ?: CredentialsFile.defaultLocation())
    }

    /** False for commands that sign in rather than use a key. */
    protected open val needsCredentials: Boolean = true
    protected val jsonOutput: Boolean by option("--json", help = "Print JSON instead of text").flag()

    override fun help(context: Context): String = helpText

    override fun run() {
        // A command group runs before its subcommand; the subcommand does the work.
        if (currentContext.invokedSubcommand != null) {
            return
        }
        runBlocking {
            val key: String? = token?.takeIf { value -> value.isNotBlank() }
                ?: credentials.takeIf { needsCredentials }?.read()?.takeIf { saved -> saved.manager == managerUrl }?.token
            clients.create(managerUrl, key).use { client ->
                try {
                    execute(client)
                } catch (refused: ShepherdApiException) {
                    throw CliktError(describe(refused))
                } catch (unreachable: IOException) {
                    throw CliktError("Cannot reach the manager at $managerUrl: ${unreachable.message ?: unreachable.javaClass.simpleName}")
                } catch (_: UnresolvedAddressException) {
                    throw CliktError("Cannot resolve the manager host in $managerUrl")
                }
            }
        }
    }

    /** Does the command's work with a client bound to the chosen manager and key. */
    protected abstract suspend fun execute(client: ShepherdClient)

    protected fun <T> printJson(serializer: KSerializer<T>, value: T) {
        echo(CliJson.encodeToString(serializer, value))
    }

    private fun describe(refused: ShepherdApiException): String {
        val message: String = refused.message.orEmpty()
        return when (refused.status) {
            401 -> "${message.trimEnd('.')}. Run mshctl login, set MSH_TOKEN or pass --token."
            403 -> "$message (the API key's role does not allow this)"
            else -> message
        }
    }
}
