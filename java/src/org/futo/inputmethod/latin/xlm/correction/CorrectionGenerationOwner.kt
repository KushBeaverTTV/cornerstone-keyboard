package org.futo.inputmethod.latin.xlm.correction

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-flight generation owner. A second begin is rejected while one is
 * in flight. [cancel] bumps the generation so in-flight work cannot publish.
 */
class CorrectionGenerationOwner {
    private val generation = AtomicLong(0)
    private val inFlight = AtomicBoolean(false)

    fun current(): Long = generation.get()

    fun tryBegin(): Long {
        if (!inFlight.compareAndSet(false, true)) return -1L
        return generation.incrementAndGet()
    }

    fun end(token: Long) {
        if (token == generation.get()) {
            inFlight.set(false)
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        inFlight.set(false)
    }
}
