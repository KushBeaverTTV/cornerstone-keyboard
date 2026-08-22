package org.futo.voiceinput.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolicyTest {

    @Test
    fun vadPolicyShouldStop_whenUseVadIsFalse_neverStops() {
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = 201, useVAD = false))
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = Int.MAX_VALUE, useVAD = false))
    }

    @Test
    fun vadPolicyShouldStop_whenHasNotTalked_neverStops() {
        assertFalse(VadPolicy.shouldStop(hasTalked = false, numConsecutiveNonSpeech = 201, useVAD = true))
        assertFalse(VadPolicy.shouldStop(hasTalked = false, numConsecutiveNonSpeech = Int.MAX_VALUE, useVAD = true))
    }

    @Test
    fun vadPolicyShouldStop_atThreshold_doesNotStop() {
        // Current tuning: STOP_CONSECUTIVE_NON_SPEECH_FRAMES = 200, so 200 is not enough.
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = 200, useVAD = true))
    }

    @Test
    fun vadPolicyShouldStop_aboveThreshold_stops() {
        assertTrue(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = 201, useVAD = true))
    }

    @Test
    fun vadPolicyShouldStop_zeroFrames_doesNotStop() {
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = 0, useVAD = true))
    }

    @Test
    fun vadPolicyConstants_arePreserved() {
        assertEquals(200, VadPolicy.STOP_CONSECUTIVE_NON_SPEECH_FRAMES)
        assertEquals(0.6f, VadPolicy.START_SOUND_PASS_SECONDS, 0.0f)
        assertEquals(0.004f, VadPolicy.TALK_RMS_THRESHOLD, 0.0f)
        assertEquals(8, VadPolicy.TALK_CONSECUTIVE_SPEECH_FRAMES)
    }

    @Test
    fun partialThrottleIntervalMs_zeroBufferedSeconds_isBaseInterval() {
        assertEquals(800L, PartialThrottle.intervalMs(0.0f))
    }

    @Test
    fun partialThrottleIntervalMs_oneBufferedSecond_adds80ms() {
        assertEquals(880L, PartialThrottle.intervalMs(1.0f))
    }

    @Test
    fun partialThrottleIntervalMs_fractionalBufferedSeconds_truncatesToLong() {
        // 800 + 0.5 * 80 = 840.0
        assertEquals(840L, PartialThrottle.intervalMs(0.5f))
        // 800 + 1.5 * 80 = 920.0
        assertEquals(920L, PartialThrottle.intervalMs(1.5f))
    }

    @Test
    fun partialThrottleIntervalMs_largeBufferedSeconds_scalesLinearly() {
        // 800 + 60 * 80 = 800 + 4800 = 5600
        assertEquals(5600L, PartialThrottle.intervalMs(60.0f))
    }

    @Test
    fun partialThrottleConstants_arePreserved() {
        assertEquals(800L, PartialThrottle.PARTIAL_DECODE_INTERVAL_MS)
        assertEquals(80, PartialThrottle.EXTRA_MS_PER_BUFFERED_SECOND)
    }

    @Test
    fun bufferGrowthNextCapacity_fromInitialCapacity_adds30Seconds() {
        // Initial AudioRecognizer buffer is 30s @ 16kHz.
        val initialCapacity = 16000 * 30
        assertEquals(16000 * 60, BufferGrowth.nextCapacity(initialCapacity))
    }

    @Test
    fun bufferGrowthNextCapacity_from60Seconds_addsAnother30Seconds() {
        val currentCapacity = 16000 * 60
        assertEquals(16000 * 90, BufferGrowth.nextCapacity(currentCapacity))
    }

    @Test
    fun bufferGrowthNextCapacity_zeroCapacity_adds30Seconds() {
        assertEquals(16000 * 30, BufferGrowth.nextCapacity(0))
    }

    @Test
    fun bufferGrowthConstants_arePreserved() {
        assertEquals(16000, BufferGrowth.SAMPLES_PER_SECOND)
        assertEquals(30, BufferGrowth.EXPAND_SECONDS)
    }
}
