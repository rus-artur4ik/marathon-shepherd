package dev.shepherd.adapter.adb

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import kotlin.test.Test

/**
 * Concurrency tests for [AdbProxyPortPool] via JetBrains Lincheck.
 *
 * Guarantees verified:
 *  - two threads never get the same port back from [AdbProxyPortPool.acquirePort]
 *  - [AdbProxyPortPool.releasePort] and [AdbProxyPortPool.acquirePort] can interleave
 *    without losing ports or handing out busy ones
 *  - the pool never exceeds its declared range
 */
class AdbProxyPortPoolLincheckTest {

    private val pool = AdbProxyPortPool(AdbProxyPortRange(startPort = 40000, endPort = 40003))

    @Operation
    fun acquire(): Int {
        return try {
            pool.acquirePort()
        } catch (error: IllegalStateException) {
            // "No free ports left" is a legitimate outcome under pressure — encode as sentinel.
            -1
        }
    }

    @Operation
    fun release(port: Int) {
        if (port in 40000..40003) {
            pool.releasePort(port)
        }
    }

    @Test
    fun `model checking finds no linearizability violations`() {
        ModelCheckingOptions()
            .iterations(50)
            .threads(3)
            .actorsPerThread(3)
            .actorsBefore(0)
            .actorsAfter(0)
            .check(this::class)
    }

    @Test
    fun `stress test does not expose lost or duplicated ports`() {
        StressOptions()
            .iterations(30)
            .threads(3)
            .actorsPerThread(4)
            .check(this::class)
    }
}
