package dev.shepherd.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import dev.shepherd.client.ShepherdClient
import dev.shepherd.protocol.CreateUserRequest
import dev.shepherd.protocol.CreatedUserResponse
import dev.shepherd.protocol.IssuedTokenResponse
import dev.shepherd.protocol.PasswordResetResponse
import dev.shepherd.protocol.PersonalTokenDto
import dev.shepherd.protocol.QuotaDto
import dev.shepherd.protocol.ShepherdApiException
import dev.shepherd.protocol.UpdateUserRequest
import dev.shepherd.protocol.UserDto
import kotlinx.serialization.builtins.ListSerializer
import java.net.InetAddress

class LoginCommand(clients: ClientFactory) :
    ShepherdCommand("login", "Sign in with a username and password and keep a personal token for mshctl", clients) {
    private val username: String by option("--username", "-u", help = "Your username").prompt("Username")
    private val password: String by option(
        "--password",
        help = "Your password (prompted for when omitted)"
    ).prompt("Password", hideInput = true)
    private val tokenName: String by option(
        "--token-name",
        help = "Name of the token, to recognise it later"
    ).default("mshctl on ${hostName()}")
    private val expiresInDays: Int by option("--expires-days", help = "Days until the token expires").int().default(90)

    override val needsCredentials: Boolean = false

    override suspend fun execute(client: ShepherdClient) {
        val issued: IssuedTokenResponse = try {
            client.createTokenWithPassword(username, password, tokenName, expiresInDays)
        } catch (refused: ShepherdApiException) {
            if (refused.status == FORBIDDEN && refused.message.orEmpty().contains("new password")) {
                throw CliktError("Your password is temporary. Choose your own with: mshctl passwd --username $username")
            }
            throw refused
        }
        if (jsonOutput) {
            printJson(IssuedTokenResponse.serializer(), issued)
            return
        }
        credentials.write(SavedCredentials(managerUrl, username, issued.token.id, issued.secret))
        echo("Signed in to $managerUrl as $username. The token '${issued.token.name}' is saved in ${credentials.file}.")
        issued.token.expiresAt?.let { expiry -> echo("It expires at $expiry; run mshctl login again then.") }
    }
}

class LogoutCommand(clients: ClientFactory) : ShepherdCommand("logout", "Revoke the saved personal token and forget it", clients) {
    override suspend fun execute(client: ShepherdClient) {
        val saved: SavedCredentials = credentials.read() ?: throw CliktError("Not signed in: ${credentials.file} does not exist")
        try {
            client.revokeToken(saved.tokenId)
        } catch (refused: ShepherdApiException) {
            // Already revoked or expired: forgetting it is all that is left to do.
            if (refused.status != UNAUTHORIZED && refused.status != NOT_FOUND) throw refused
        }
        credentials.delete()
        echo("Signed out of ${saved.manager}; the token is revoked.")
    }
}

class PasswordCommand(clients: ClientFactory) : ShepherdCommand("passwd", "Change your password", clients) {
    private val username: String? by option(
        "--username",
        "-u",
        help = "Change it by signing in as this user, e.g. to replace a temporary password"
    )
    private val current: String by option(
        "--current",
        help = "The current password (prompted for when omitted)"
    ).prompt("Current password", hideInput = true)
    private val new: String by option("--new", help = "The new password (prompted for when omitted)")
        .prompt("New password", hideInput = true, requireConfirmation = true)

    override val needsCredentials: Boolean get() = username == null

    override suspend fun execute(client: ShepherdClient) {
        val name: String? = username
        if (name != null) {
            client.changePasswordWithLogin(name, current, new)
        } else {
            client.changePassword(current, new)
        }
        echo("Password changed.")
    }
}

class TokensCommand(clients: ClientFactory) : ShepherdCommand("tokens", "List your personal API tokens", clients) {
    override val invokeWithoutSubcommand: Boolean = true

    override suspend fun execute(client: ShepherdClient) {
        val tokens: List<PersonalTokenDto> = client.listTokens()
        if (jsonOutput) {
            printJson(ListSerializer(PersonalTokenDto.serializer()), tokens)
        } else {
            echo(formatTokens(tokens))
        }
    }
}

class TokenCreate(clients: ClientFactory) : ShepherdCommand("create", "Create a personal API token and print it once", clients) {
    private val name: String by option("--name", help = "What the token is for").required()
    private val expiresInDays: Int? by option("--expires-days", help = "Days until it expires (default: never)").int()

    override suspend fun execute(client: ShepherdClient) {
        val issued: IssuedTokenResponse = client.createToken(name, expiresInDays)
        if (jsonOutput) {
            printJson(IssuedTokenResponse.serializer(), issued)
        } else {
            echo("Created token '${issued.token.name}' (${issued.token.id}). It is shown only this once:\n${issued.secret}")
        }
    }
}

class TokenRevoke(clients: ClientFactory) : ShepherdCommand("revoke", "Revoke one of your personal API tokens", clients) {
    private val tokenId: String by argument("TOKEN_ID")

    override suspend fun execute(client: ShepherdClient) {
        val revoked: PersonalTokenDto = client.revokeToken(tokenId)
        if (jsonOutput) {
            printJson(PersonalTokenDto.serializer(), revoked)
        } else {
            echo("Revoked token '${revoked.name}' (${revoked.id}).")
        }
    }
}

class UsersCommand(clients: ClientFactory) : ShepherdCommand("users", "List people who sign in (admin)", clients) {
    override val invokeWithoutSubcommand: Boolean = true

    private val all: Boolean by option("--all", help = "Include disabled users").flag()

    override suspend fun execute(client: ShepherdClient) {
        val users: List<UserDto> = client.listUsers(includeDisabled = all)
        if (jsonOutput) {
            printJson(ListSerializer(UserDto.serializer()), users)
        } else {
            echo(formatUsers(users))
        }
    }
}

class UserCreate(clients: ClientFactory) : ShepherdCommand("create", "Create a local user; prints a one-time password (admin)", clients) {
    private val username: String by option("--username", help = "Username").required()
    private val role: String by option("--role", help = "admin, user or viewer").choice("admin", "user", "viewer").default("user")
    private val displayName: String? by option("--display-name")
    private val email: String? by option("--email")
    private val maxDevices: Int? by option("--max-devices", help = "Devices the user may hold at once").int()

    override suspend fun execute(client: ShepherdClient) {
        val created: CreatedUserResponse = client.createUser(
            CreateUserRequest(
                username = username,
                displayName = displayName,
                email = email,
                role = role,
                quota = QuotaDto(maxDevices = maxDevices)
            )
        )
        if (jsonOutput) {
            printJson(CreatedUserResponse.serializer(), created)
        } else {
            echo("Created ${created.user.role} ${created.user.username} (${created.user.id}).")
            created.temporaryPassword?.let { password ->
                echo("One-time password, shown only now; they choose their own at first sign-in:\n$password")
            }
        }
    }
}

class UserUpdate(clients: ClientFactory) : ShepherdCommand(
    "update",
    "Change a user's role, name, email or whether they are active (admin)",
    clients
) {
    private val userId: String by argument("USER_ID")
    private val role: String? by option("--role", help = "admin, user or viewer").choice("admin", "user", "viewer")
    private val displayName: String? by option("--display-name")
    private val email: String? by option("--email")
    private val active: String? by option("--active", help = "true to enable, false to disable").choice("true", "false")

    override suspend fun execute(client: ShepherdClient) {
        if (role == null && displayName == null && email == null && active == null) {
            throw UsageError("Nothing to change: pass --role, --display-name, --email or --active")
        }
        val updated: UserDto = client.updateUser(
            userId,
            UpdateUserRequest(displayName = displayName, email = email, role = role, active = active?.toBooleanStrict())
        )
        if (jsonOutput) printJson(UserDto.serializer(), updated) else echo(formatUser(updated))
    }
}

class UserResetPassword(clients: ClientFactory) : ShepherdCommand(
    "reset-password",
    "Give a local user a new one-time password (admin)",
    clients
) {
    private val userId: String by argument("USER_ID")

    override suspend fun execute(client: ShepherdClient) {
        val reset: PasswordResetResponse = client.resetUserPassword(userId)
        if (jsonOutput) {
            printJson(PasswordResetResponse.serializer(), reset)
        } else {
            echo("The user is signed out everywhere. One-time password, shown only now:\n${reset.temporaryPassword}")
        }
    }
}

class UserDisable(clients: ClientFactory) : ShepherdCommand("disable", "Disable a user and sign them out (admin)", clients) {
    private val userId: String by argument("USER_ID")
    private val releaseSessions: Boolean by option("--release-sessions", help = "Also release their device sessions").flag()

    override suspend fun execute(client: ShepherdClient) {
        val disabled: UserDto = client.disableUser(userId, releaseSessions)
        if (jsonOutput) printJson(UserDto.serializer(), disabled) else echo("Disabled ${disabled.username} (${disabled.id}).")
    }
}

internal fun formatTokens(tokens: List<PersonalTokenDto>): String {
    if (tokens.isEmpty()) {
        return "No personal tokens. Create one with: mshctl tokens create --name <what it is for>"
    }
    return table(
        listOf("ID", "NAME", "PREFIX", "CREATED", "LAST USED", "EXPIRES"),
        tokens.map { token ->
            listOf(
                token.id,
                token.name,
                "${token.prefix}...",
                token.createdAt,
                token.lastUsedAt ?: "-",
                token.expiresAt ?: "never"
            )
        }
    )
}

internal fun formatUsers(users: List<UserDto>): String {
    if (users.isEmpty()) {
        return "No users."
    }
    return table(
        listOf("ID", "USERNAME", "NAME", "ROLE", "SOURCE", "STATE", "LAST SIGN-IN"),
        users.map { user ->
            listOf(
                user.id,
                user.username,
                user.displayName ?: "-",
                user.role + if (user.roleManagedByProvider) " (${user.provider})" else "",
                user.provider?.let { provider -> "${user.source}:$provider" } ?: user.source,
                when {
                    !user.active -> "disabled"
                    user.mustChangePassword -> "must change password"
                    else -> "active"
                },
                user.lastLoginAt ?: "-"
            )
        }
    )
}

internal fun formatUser(user: UserDto): String = buildString {
    appendLine("User ${user.username} (${user.id})")
    user.displayName?.let { name -> appendLine("Name: $name") }
    user.email?.let { email -> appendLine("Email: $email") }
    appendLine("Role: ${user.role}" + if (user.roleManagedByProvider) " (from ${user.provider} groups)" else "")
    appendLine("Signs in with: ${user.provider?.let { provider -> "${user.source} ($provider)" } ?: user.source}")
    appendLine("Quota: ${formatQuota(user.effectiveQuota)}")
    append(if (user.active) "Active" else "Disabled since ${user.disabledAt}")
}

private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("this computer")

private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
