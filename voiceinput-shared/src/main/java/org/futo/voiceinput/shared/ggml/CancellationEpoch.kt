package org.futo.voiceinput.shared.ggml

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic cancellation generation. Atomic increments prevent concurrent
 * invalidations from being lost, while captured values let in-flight work fail
 * closed before publishing into a newer recording session.
 */
internal class CancellationEpoch {
    private val value = AtomicLong(0)

    fun current(): Long = value.get()

    fun invalidate(): Long = value.incrementAndGet()

    fun isCurrent(captured: Long): Boolean = value.get() == captured
}
