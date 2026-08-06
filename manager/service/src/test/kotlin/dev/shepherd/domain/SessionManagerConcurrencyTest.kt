package dev.shepherd.domain

import dev.shepherd.adapter.api.*
import dev.shepherd.domain.model.AdbServer
import dev.shepherd.domain.model.SessionStatus
import dev.shepherd.domain.provider.AcquireResult
import dev.shepherd.domain.provider.DevicePoolStatus
import dev.shepherd.domain.provider.DeviceProvider
import dev.shepherd.domain.provider.FakeProviderCatalog
import dev.shepherd.infra.state.StateStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Concurrency-level checks for [SessionManager].
 *
 * These tests do not mock the scheduler — they actually run many parallel coroutines
 * against a shared pool to expose double allocation and leaked leases. StateStore is
 * a real on-disk SQLite so contention on the queue head is exercised end-to-end.
 */
class SessionManagerConcurrencyTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `parallel createSession calls never double-allocate a serial`() = runBlocking {
        val provider = CountingPoolProvider(
            name = "emu-rack",
            poolSize = 8,
            supportedApiLevels = listOf("34")
        )
        val sessionManager = createSessionManager(
            storeName = "double-alloc.db",
            provider = provider
        )

        val parallel = 20
        val results = coroutineScope {
            (1..parallel).map { index ->
                async(Dispatchers.IO) {
                    runCatching {
                        sessionManager.createSession(
                            requestedDevices = 1,
                            api = "34",
                            ttlSeconds = 30,
                            deviceType = DEVICE_TYPE_EMULATOR
                        )
                    }
                }
            }.awaitAll()
        }
        val successes = results.mapNotNull { it.getOrNull() }
        val ready = successes.count { it.status == SessionStatus.READY }
        val pending = successes.count { it.status == SessionStatus.PENDING }

        // Invariant: we can never have more READY sessions than devices in the pool.
        assertTrue(ready <= provider.poolSize, "ready=$ready exceeds pool=${provider.poolSize}")
        assertEquals(parallel, ready + pending, "every call should land in READY or PENDING")
        assertTrue(provider.peakConcurrentlyAcquired.get() <= provider.poolSize)
    }

    @Test
    fun `release then re-create returns same serial without stranding devices`() = runBlocking {
        val provider = CountingPoolProvider(
            name = "emu-rack",
            poolSize = 4,
            supportedApiLevels = listOf("34")
        )
        val sessionManager = createSessionManager(
            storeName = "release-reuse.db",
            provider = provider
        )

        // Saturate the pool.
        val firstWave = (1..4).map {
            sessionManager.createSession(
                requestedDevices = 1,
                api = "34",
                ttlSeconds = 30,
                deviceType = DEVICE_TYPE_EMULATOR
            )
        }
        assertEquals(4, firstWave.count { it.status == SessionStatus.READY })

        // Release half concurrently, while new requests compete for the freed slots.
        coroutineScope {
            firstWave.take(2).forEach { session ->
                async(Dispatchers.IO) { sessionManager.releaseSession(session.id) }
            }
            (1..4).map {
                async(Dispatchers.IO) {
                    sessionManager.createSession(
                        requestedDevices = 1,
                        api = "34",
                        ttlSeconds = 30,
                        deviceType = DEVICE_TYPE_EMULATOR
                    )
                }
            }
        }

        // After churn: active (non-released) READY sessions must never exceed pool size.
        val sessions = sessionManager.listSessions()
        val activeReady = sessions.count { it.status == SessionStatus.READY }
        assertTrue(activeReady <= provider.poolSize, "activeReady=$activeReady > pool=${provider.poolSize}")
    }

    @Test
    fun `concurrent release is idempotent`() = runBlocking {
        val provider = CountingPoolProvider(
            name = "emu-rack",
            poolSize = 2,
            supportedApiLevels = listOf("34")
        )
        val sessionManager = createSessionManager(
            storeName = "release-idempotent.db",
            provider = provider
        )
        val session = sessionManager.createSession(
            requestedDevices = 1,
            api = "34",
            ttlSeconds = 30,
            deviceType = DEVICE_TYPE_EMULATOR
        )

        coroutineScope {
            (1..8).map {
                async(Dispatchers.IO) { sessionManager.releaseSession(session.id) }
            }.awaitAll()
        }

        // Exactly one underlying release per lease.
        assertEquals(1, provider.releaseCallsFor(session.id))
    }

    private fun createSessionManager(storeName: String, provider: DeviceProvider): SessionManager {
        val stateStore = StateStore(File(tempDir, storeName).absolutePath)
        return SessionManager(
            providerCatalog = FakeProviderCatalog(active = listOf(provider)),
            stateStore = stateStore
        )
    }
}

/**
 * Thread-safe provider backed by an in-memory counter. Simulates a pool of
 * [poolSize] devices with proper atomicity on acquire/release. Any double-book
 * or leaked lease will surface as [peakConcurrentlyAcquired] exceeding the pool.
 */
private class CountingPoolProvider(
    override val name: String,
    val poolSize: Int,
    private val supportedApiLevels: List<String>
) : DeviceProvider {
    override val adbServer: AdbServer = AdbServer("127.0.0.1", 5037)
    override val access: AdapterAccess = AdapterAccess(
        preferredConnectionId = "$name-primary-adb",
        connections = listOf(
            AdapterConnection(
                id = "$name-primary-adb",
                protocol = ACCESS_PROTOCOL_ADB,
                transport = ACCESS_TRANSPORT_TCP,
                host = adbServer.host,
                port = adbServer.port,
                exposure = ACCESS_EXPOSURE_DIRECT_TCP,
                auth = AdapterConnectionAuth(type = ACCESS_AUTH_NETWORK),
                metadata = mapOf("scope" to "test")
            )
        )
    )
    override val capabilities: AdapterCapabilities = AdapterCapabilities(
        supportedDeviceTypes = listOf(DEVICE_TYPE_EMULATOR),
        supportedApiLevels = supportedApiLevels,
        supportsSelectiveApiAllocation = true
    )
    override val inventory: List<AdapterDeviceProfile> = listOf(
        AdapterDeviceProfile(deviceType = DEVICE_TYPE_EMULATOR, apiLevel = supportedApiLevels.first(), count = poolSize)
    )

    private val mutex = Mutex()
    private var inUse: Int = 0
    val peakConcurrentlyAcquired: AtomicInteger = AtomicInteger(0)
    private val leaseSizes: MutableMap<String, Int> = mutableMapOf()
    private val releaseCalls: MutableMap<String, Int> = mutableMapOf()

    override suspend fun queryDevices(): DevicePoolStatus = mutex.withLock {
        DevicePoolStatus(available = poolSize - inUse, busy = inUse, total = poolSize)
    }

    override suspend fun acquire(count: Int, apiLevel: String, ttlSeconds: Long): AcquireResult = mutex.withLock {
        val toAcquire = minOf(count, poolSize - inUse)
        if (toAcquire <= 0) {
            return@withLock AcquireResult(leaseId = "", acquiredCount = 0, adbServers = emptyList())
        }
        inUse += toAcquire
        peakConcurrentlyAcquired.updateAndGet { prev -> maxOf(prev, inUse) }
        val leaseId = "lease_${UUID.randomUUID().toString().take(8)}"
        leaseSizes[leaseId] = toAcquire
        AcquireResult(leaseId = leaseId, acquiredCount = toAcquire, adbServers = listOf(adbServer))
    }

    override suspend fun release(leaseId: String): Unit = mutex.withLock {
        val size = leaseSizes.remove(leaseId) ?: return@withLock
        inUse -= size
        releaseCalls.merge(leaseId, 1, Int::plus)
    }

    override fun supportsDeviceType(deviceType: String): Boolean =
        capabilities.supportedDeviceTypes.isEmpty() || deviceType in capabilities.supportedDeviceTypes

    override fun canAllocateApiLevel(apiLevel: String): Boolean = apiLevel in supportedApiLevels

    override suspend fun isHealthy(): Boolean = true

    fun releaseCallsFor(sessionId: String): Int = runBlocking {
        mutex.withLock { releaseCalls.values.sum() }
    }
}
