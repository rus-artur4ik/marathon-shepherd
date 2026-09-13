package dev.shepherd.infra.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Slows down password guessing. After [maxFailures] failed sign-ins in a row a username is locked
 * for [lockout]; an address gets four times as many tries, so a team behind one NAT is not locked
 * out by one person's typos. The state lives in memory: there is one manager per database.
 */
class LoginThrottle(
    private val clock: Clock = Clock.systemUTC(),
    private val maxFailures: () -> Int,
    private val lockout: () -> Duration
) {
    private data class Failures(val count: Int, val last: Instant, val lockedUntil: Instant?)

    private val failures = ConcurrentHashMap<String, Failures>()

    /** When sign-in is possible again for this username or address, or null if it is now. */
    fun lockedUntil(username: String, address: String?): Instant? {
        val now: Instant = clock.instant()
        return listOfNotNull(userKey(username), address?.let(::addressKey))
            .mapNotNull { key -> failures[key]?.lockedUntil?.takeIf { until -> until.isAfter(now) } }
            .maxOrNull()
    }

    fun recordFailure(username: String, address: String?) {
        val now: Instant = clock.instant()
        record(userKey(username), maxFailures(), now)
        address?.let { value -> record(addressKey(value), maxFailures() * ADDRESS_FACTOR, now) }
        if (failures.size > MAX_TRACKED) {
            forgetStale(now)
        }
    }

    /** A correct password clears the username's failures; the address keeps its count. */
    fun recordSuccess(username: String) {
        failures.remove(userKey(username))
    }

    private fun record(key: String, limit: Int, now: Instant) {
        failures.compute(key) { _, previous ->
            // Failures spread out over more than a lockout period do not add up.
            val count: Int = if (previous == null || Duration.between(previous.last, now) > lockout()) 1 else previous.count + 1
            Failures(count = count, last = now, lockedUntil = if (count >= limit) now.plus(lockout()) else previous?.lockedUntil)
        }
    }

    private fun forgetStale(now: Instant) {
        failures.entries.removeIf { (_, entry) ->
            Duration.between(entry.last, now) > lockout() && (entry.lockedUntil == null || entry.lockedUntil.isBefore(now))
        }
    }

    private fun userKey(username: String): String = "user:" + username.trim().lowercase()

    private fun addressKey(address: String): String = "address:$address"

    private companion object {
        const val ADDRESS_FACTOR = 4

        /** Spraying random usernames must not grow memory without bound. */
        const val MAX_TRACKED = 10_000
    }
}
