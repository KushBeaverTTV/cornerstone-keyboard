package org.futo.voiceinput.shared.ggml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CancellationEpochTest {
    @Test
    fun concurrentInvalidationsAreNeverLost() {
        val epoch = CancellationEpoch()
        val workers = 16
        val iterations = 5_000
        val ready = CyclicBarrier(workers)
        val pool = Executors.newFixedThreadPool(workers)

        try {
            val futures = List(workers) {
                pool.submit {
                    ready.await()
                    repeat(iterations) { epoch.invalidate() }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            assertEquals(workers.toLong() * iterations, epoch.current())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun invalidationMakesCapturedEpochStale() {
        val epoch = CancellationEpoch()
        val captured = epoch.current()

        assertTrue(epoch.isCurrent(captured))
        epoch.invalidate()
        assertFalse(epoch.isCurrent(captured))
    }
}
