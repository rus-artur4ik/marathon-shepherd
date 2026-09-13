package dev.shepherd.infra.auth

import dev.shepherd.domain.auth.Role
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/** Someone a directory vouched for: LDAP after their password bound, or an OIDC provider after a verified ID token. */
data class ExternalIdentity(
    val source: UserSource,
    /** `ldap`, or the OIDC provider's id. */
    val provider: String,
    /** The LDAP DN or the OIDC subject: what recognises the person at the next sign-in. */
    val externalId: String,
    val username: String,
    val displayName: String?,
    val email: String?,
    val groups: Set<String>,
    val roleMapping: Map<String, String>,
    val defaultRole: String?
)

/** A sign-in that did not succeed, with a message safe to show: it never says whether the username exists. */
class SignInFailure(message: String, val retryAfterSeconds: Long? = null) : RuntimeException(message)

/** The directory could not be asked: unreachable, too slow, or it refused the service account. */
class DirectoryUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object RoleMapping {
    private val ORDER: List<Role> = listOf(Role.ADMIN, Role.USER, Role.VIEWER)

    /**
     * The highest role any of [groups] maps to, else [defaultRole]; null when neither applies.
     * Group names may be written with or without a leading `/` (Keycloak group paths); Active
     * Directory compares them regardless of case, OIDC providers exactly.
     */
    fun resolve(groups: Set<String>, mapping: Map<String, String>, defaultRole: String?, ignoreCase: Boolean): Role? {
        val normalized: Set<String> = groups.map { group -> normalize(group, ignoreCase) }.toSet()
        val mapped: List<Role> = mapping.entries
            .filter { (group, _) -> normalize(group, ignoreCase) in normalized }
            .map { (_, role) -> Role.parse(role) }
        return mapped.minByOrNull { role -> ORDER.indexOf(role) } ?: defaultRole?.let(Role::parse)
    }

    private fun normalize(group: String, ignoreCase: Boolean): String = group.trim().trimStart('/').let { name ->
        if (ignoreCase) name.lowercase() else name
    }
}

internal object RandomTokens {
    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun urlSafe(bytes: Int): String = encoder.encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun id(prefix: String): String =
        prefix + urlSafe(ID_BYTES).lowercase().filter { character -> character.isLetterOrDigit() }.take(ID_LENGTH)

    private const val ID_BYTES = 18
    private const val ID_LENGTH = 16
}

/** Writes a secret shown once at first start into a file only its owner can read. */
internal object OwnerOnlyFile {
    private val logger = LoggerFactory.getLogger(OwnerOnlyFile::class.java)

    fun write(file: File, secret: String) {
        try {
            file.parentFile?.mkdirs()
            val path = file.toPath()
            Files.deleteIfExists(path)
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } catch (_: UnsupportedOperationException) {
                logger.warn("File system does not support POSIX permissions; restrict access to {} manually", file)
                Files.createFile(path)
            }
            Files.writeString(path, secret + System.lineSeparator())
        } catch (error: IOException) {
            logger.warn("Could not write {}: {}", file, error.message)
        }
    }
}
