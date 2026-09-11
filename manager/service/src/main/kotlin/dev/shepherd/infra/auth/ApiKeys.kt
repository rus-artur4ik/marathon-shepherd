package dev.shepherd.infra.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * API key format and storage.
 *
 * Keys are `msh_` plus 40 random base62 characters (~238 bits). Only a SHA-256 digest is
 * stored: a fast hash is enough for high-entropy random keys, and it lets authentication
 * look the key up by digest instead of scanning every client.
 */
object ApiKeys {
    const val PREFIX: String = "msh_"
    private const val SECRET_LENGTH: Int = 40
    private const val DISPLAY_LENGTH: Int = 8
    private const val ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    fun generate(): String = PREFIX + (1..SECRET_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** What listings show: enough to tell keys apart, useless for authenticating. */
    fun displayPrefix(key: String): String = key.take(PREFIX.length + DISPLAY_LENGTH)

    fun hash(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    /** Compares secrets without leaking their common prefix through timing. */
    fun constantTimeEquals(actual: String, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val actualDigest = digest.digest(actual.toByteArray(Charsets.UTF_8))
        val expectedDigest = digest.digest(expected.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(actualDigest, expectedDigest)
    }
}
