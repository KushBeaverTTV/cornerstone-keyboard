package org.futo.voiceinput.shared.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EngineCacheTest {

    @Test
    fun getOrLoad_cachesResult() {
        val cache = EngineCache<String, Any>()
        val loads = AtomicInteger(0)

        val first = cache.getOrLoad("model") { loads.incrementAndGet(); Any() }
        val second = cache.getOrLoad("model") { loads.incrementAndGet(); Any() }

        assertSame(first, second)
        assertEquals(1, loads.get())
    }

    @Test
    fun getOrLoad_concurrentCallers_shareSingleLoad() {
        val cache = EngineCache<String, Any>()
        val loads = AtomicInteger(0)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val results = arrayOfNulls<Any>(threads)

        repeat(threads) { i ->
            pool.execute {
                ready.countDown()
                go.await()
                results[i] = cache.getOrLoad("model") {
                    loads.incrementAndGet()
                    // Slow load: without single-flight, every racer would enter here.
                    Thread.sleep(50)
                    Any()
                }
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals("concurrent callers must not trigger a second load", 1, loads.get())
        results.forEach { assertSame(results[0], it) }
    }

    @Test
    fun getOrLoad_failedLoad_isNotCached() {
        val cache = EngineCache<String, Any>()
        val loads = AtomicInteger(0)

        try {
            cache.getOrLoad("model") { loads.incrementAndGet(); throw RuntimeException("load failed") }
        } catch (expected: RuntimeException) {
        }

        val value = cache.getOrLoad("model") { loads.incrementAndGet(); Any() }
        assertEquals(2, loads.get())
        assertSame(value, cache.peek("model"))
    }

    @Test
    fun peek_neverTriggersLoad() {
        val cache = EngineCache<String, Any>()
        assertNull(cache.peek("model"))

        val value = cache.getOrLoad("model") { Any() }
        assertSame(value, cache.peek("model"))
    }

    @Test
    fun clear_dropsEntries() {
        val cache = EngineCache<String, Any>()
        cache.getOrLoad("model") { Any() }
        cache.clear()

        assertNull(cache.peek("model"))
        assertEquals(0, cache.entries.size)
    }
}
