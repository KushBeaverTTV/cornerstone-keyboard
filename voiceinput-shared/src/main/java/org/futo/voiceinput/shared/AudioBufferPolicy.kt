package org.futo.voiceinput.shared

import java.nio.FloatBuffer

object AudioBufferPolicy {
    private const val WARM_CAPACITY =
        BufferGrowth.SAMPLES_PER_SECOND * BufferGrowth.EXPAND_SECONDS

    fun reset(buffer: FloatBuffer): FloatBuffer =
        if (buffer.capacity() == WARM_CAPACITY) {
            buffer.apply { clear() }
        } else {
            FloatBuffer.allocate(WARM_CAPACITY)
        }
}
