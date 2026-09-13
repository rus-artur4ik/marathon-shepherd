package dev.shepherd.infra.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password storage: PBKDF2-HMAC-SHA256 with a random salt per password, in the self-describing
 * form `pbkdf2-sha256$<iterations>$<salt>$<hash>`, so the work factor can be raised later without
 * locking anyone out.
 */
class PasswordHasher(val iterations: Int = DEFAULT_ITERATIONS) {
    private val random = SecureRandom()

    /** A hash of a password nobody knows, for checks against users who do not exist. */
    private val decoy: String by lazy { hash(generate()) }

    init {
        require(iterations > 0) { "iterations must be positive" }
    }

    fun hash(password: String): String {
        require(password.length <= MAX_PASSWORD_LENGTH) { "The password must be at most $MAX_PASSWORD_LENGTH characters long" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return listOf(
            SCHEME,
            iterations.toString(),
            encoder.encodeToString(salt),
            encoder.encodeToString(derive(password, salt, iterations))
        )
            .joinToString(SEPARATOR)
    }

    fun verify(password: String, stored: String): Boolean {
        if (password.length > MAX_PASSWORD_LENGTH) {
            return false
        }
        val parts: List<String> = stored.split(SEPARATOR)
        if (parts.size != PARTS || parts[0] != SCHEME) {
            return false
        }
        val rounds: Int = parts[1].toIntOrNull()?.takeIf { value -> value > 0 } ?: return false
        val salt: ByteArray = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected: ByteArray = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, rounds), expected)
    }

    /**
     * Spends the effort of a real check, so refusing an unknown username takes as long as refusing
     * a wrong password and response times do not reveal which accounts exist.
     */
    fun verifyDecoy(password: String) {
        verify(password.take(MAX_PASSWORD_LENGTH), decoy)
    }

    /** True for hashes made with fewer iterations than this hasher uses. */
    fun needsRehash(stored: String): Boolean =
        stored.split(SEPARATOR).getOrNull(1)?.toIntOrNull()?.let { rounds -> rounds < iterations } ?: true

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        /** OWASP's recommendation for PBKDF2-HMAC-SHA256. */
        const val DEFAULT_ITERATIONS: Int = 600_000
        const val MAX_PASSWORD_LENGTH: Int = 1024
        private const val SCHEME = "pbkdf2-sha256"
        private const val SEPARATOR = "$"
        private const val PARTS = 4
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256

        /** No 0/O or 1/l/I, so a generated password can be read out and typed. */
        private const val READABLE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
        private val decoder: Base64.Decoder = Base64.getDecoder()

        fun generate(length: Int = 20): String {
            val random = SecureRandom()
            return (1..length).map { READABLE_ALPHABET[random.nextInt(READABLE_ALPHABET.length)] }.joinToString("")
        }

        /** Throws [IllegalArgumentException] saying what a new password lacks. */
        fun requireAcceptable(password: String, username: String, minLength: Int) {
            require(password.length >= minLength) { "The password must be at least $minLength characters long" }
            require(password.length <= MAX_PASSWORD_LENGTH) { "The password must be at most $MAX_PASSWORD_LENGTH characters long" }
            require(password.isNotBlank()) { "The password must not be blank" }
            require(!password.equals(username, ignoreCase = true)) { "The password must not be the username" }
        }
    }
}
