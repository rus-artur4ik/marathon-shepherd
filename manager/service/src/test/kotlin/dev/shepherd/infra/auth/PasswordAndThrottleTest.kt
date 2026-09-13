package dev.shepherd.infra.auth

import dev.shepherd.domain.auth.Role
import dev.shepherd.domain.provider.SettableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordAndThrottleTest {

    private val hasher = PasswordHasher(iterations = 1_000)

    @Test
    fun `a password verifies against its own hash only, each with its own salt`() {
        val hash: String = hasher.hash("correct horse battery")

        assertTrue(hasher.verify("correct horse battery", hash))
        assertFalse(hasher.verify("correct horse batterY", hash))
        assertNotEquals(hash, hasher.hash("correct horse battery"))
        assertTrue(hash.startsWith("pbkdf2-sha256\$1000\$"))
    }

    @Test
    fun `older hashes still verify and ask to be upgraded, garbage never verifies`() {
        val weaker: String = PasswordHasher(iterations = 500).hash("correct horse battery")

        assertTrue(hasher.verify("correct horse battery", weaker))
        assertTrue(hasher.needsRehash(weaker))
        assertFalse(hasher.needsRehash(hasher.hash("correct horse battery")))
        assertFalse(hasher.verify("correct horse battery", "correct horse battery"))
        assertFalse(hasher.verify("anything", "pbkdf2-sha256\$x\$y\$z"))
    }

    @Test
    fun `new passwords must be long enough and not the username`() {
        assertFailsWith<IllegalArgumentException> { PasswordHasher.requireAcceptable("short", "alice", minLength = 10) }
        assertFailsWith<IllegalArgumentException> { PasswordHasher.requireAcceptable("Alice-In-Chains", "alice-in-chains", minLength = 10) }
        PasswordHasher.requireAcceptable("correct horse battery", "alice", minLength = 10)
        assertEquals(20, PasswordHasher.generate().length)
    }

    @Test
    fun `a username is locked after repeated failures, and free again after the lockout`() {
        val clock = SettableClock(Instant.parse("2026-09-13T10:00:00Z"))
        val throttle = LoginThrottle(clock, maxFailures = { 3 }, lockout = { Duration.ofMinutes(15) })

        repeat(2) { throttle.recordFailure("alice", "10.0.0.1") }
        val beforeThird: Instant? = throttle.lockedUntil("alice", null)
        throttle.recordFailure("ALICE", "10.0.0.2")
        val locked: Instant? = throttle.lockedUntil("alice", null)
        clock.now = clock.now.plus(Duration.ofMinutes(16))

        assertNull(beforeThird)
        assertEquals(Instant.parse("2026-09-13T10:15:00Z"), locked)
        assertNull(throttle.lockedUntil("alice", null))
    }

    @Test
    fun `a correct password clears the count, and an address gets more tries than a username`() {
        val clock = SettableClock(Instant.parse("2026-09-13T10:00:00Z"))
        val throttle = LoginThrottle(clock, maxFailures = { 3 }, lockout = { Duration.ofMinutes(15) })

        repeat(2) { throttle.recordFailure("bob", null) }
        throttle.recordSuccess("bob")
        repeat(2) { throttle.recordFailure("bob", null) }
        val bobAfterSuccess: Instant? = throttle.lockedUntil("bob", null)
        repeat(11) { attempt -> throttle.recordFailure("sprayed-$attempt", "10.0.0.9") }
        val addressBefore: Instant? = throttle.lockedUntil("someone-new", "10.0.0.9")
        throttle.recordFailure("sprayed-11", "10.0.0.9")

        assertNull(bobAfterSuccess)
        assertNull(addressBefore)
        assertTrue(throttle.lockedUntil("someone-new", "10.0.0.9") != null)
    }

    @Test
    fun `the highest mapped role wins and the default covers everyone else`() {
        val mapping = mapOf("qa" to "user", "shepherd-admins" to "admin")

        assertEquals(Role.ADMIN, RoleMapping.resolve(setOf("qa", "/shepherd-admins"), mapping, "viewer", ignoreCase = false))
        assertEquals(Role.USER, RoleMapping.resolve(setOf("qa"), mapping, "viewer", ignoreCase = false))
        assertEquals(Role.VIEWER, RoleMapping.resolve(setOf("sales"), mapping, "viewer", ignoreCase = false))
        assertNull(RoleMapping.resolve(setOf("sales"), mapping, null, ignoreCase = false))
        assertNull(RoleMapping.resolve(setOf("QA"), mapping, null, ignoreCase = false))
        assertEquals(Role.USER, RoleMapping.resolve(setOf("QA"), mapping, null, ignoreCase = true))
    }
}
