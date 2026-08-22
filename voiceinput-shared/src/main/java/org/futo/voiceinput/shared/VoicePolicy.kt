package org.futo.voiceinput.shared

/**
 * Pure, stateless voice-recording policy decisions extracted from AudioRecognizer.
 *
 * These functions encode the currently tuned constants verbatim; they exist so
 * future changes to VAD stop behaviour, partial-decode throttling, or buffer
 * growth have a regression net.
 */

object VadPolicy {
    // KBP: 66 frames (~2s) cut people off mid-thought on hesitations.
    // ~6s of consecutive silence before auto-stopping.
    const val STOP_CONSECUTIVE_NON_SPEECH_FRAMES = 200

    // Don't treat the start sound as user speech; gate hasTalked until 0.6s
    // of audio has been collected.
    const val START_SOUND_PASS_SECONDS = 0.6f

    // Near-field gate: this RMS level counts as speech, so quiet far-field
    // noise doesn't mark the user as having talked.
    const val TALK_RMS_THRESHOLD = 0.004f

    // Alternatively, this many consecutive VAD speech frames count as talking.
    const val TALK_CONSECUTIVE_SPEECH_FRAMES = 8

    /**
     * Returns true when the recorder should auto-stop because the user has
     * already spoken and there has been enough consecutive silence.
     */
    fun shouldStop(hasTalked: Boolean, numConsecutiveNonSpeech: Int, useVAD: Boolean): Boolean {
        return hasTalked && (numConsecutiveNonSpeech > STOP_CONSECUTIVE_NON_SPEECH_FRAMES) && useVAD
    }
}

object PartialThrottle {
    // Base re-decode interval while streaming partial transcripts.
    const val PARTIAL_DECODE_INTERVAL_MS = 800L

    // Add 80ms of interval per buffered second so long notes don't lag.
    const val EXTRA_MS_PER_BUFFERED_SECOND = 80

    /**
     * Computes the minimum time between streaming partial decodes for a buffer
     * that is [bufferedSeconds] seconds long.
     */
    fun intervalMs(bufferedSeconds: Float): Long {
        return (PARTIAL_DECODE_INTERVAL_MS + bufferedSeconds * EXTRA_MS_PER_BUFFERED_SECOND).toLong()
    }
}

object BufferGrowth {
    const val SAMPLES_PER_SECOND = 16000

    // Each expansion allocates an extra 30 seconds of audio buffer.
    const val EXPAND_SECONDS = 30

    // Hard cap: never accumulate more than 5 minutes of 16kHz float audio.
    const val MAX_BUFFER_SECONDS = 5 * 60
    const val MAX_CAPACITY = SAMPLES_PER_SECOND * MAX_BUFFER_SECONDS

    /**
     * Returns the next buffer capacity given the current [capacity].
     * Current strategy: add 30 seconds worth of samples each expansion.
     */
    fun nextCapacity(capacity: Int): Int {
        return capacity + SAMPLES_PER_SECOND * EXPAND_SECONDS
    }

    /**
     * Returns true when [capacity] has reached the maximum allowed buffer size.
     * At this cap AudioRecognizer triggers the normal finish path instead of
     * growing the buffer further.
     */
    fun isAtCap(capacity: Int): Boolean {
        return capacity >= MAX_CAPACITY
    }
}
