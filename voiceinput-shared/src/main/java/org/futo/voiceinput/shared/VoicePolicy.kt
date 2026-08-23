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

/**
 * Stop-button / end-of-utterance policy. Extracted so the IME cannot quietly
 * reintroduce the pinwheel or a second beam decode.
 *
 * The floating Cornerstone Voice bubble (com.kbphq.voice 0.2.4) never waits
 * on a second full-buffer beam pass. Keyboard voice must match that contract:
 * show live partials, commit the last partial immediately when one exists,
 * greedy-only if a final decode is still required, never block insert on polish.
 */
object FinishPolicy {
    const val SHOW_INLINE_PARTIALS = true
    const val FINAL_DECODING_METHOD = "greedy_search"
    const val BLOCK_INSERT_ON_POLISH = false

    fun shouldShowPinwheel(hasUsablePartial: Boolean): Boolean = !hasUsablePartial

    fun shouldCommitPartialImmediately(lastPartial: String): Boolean =
        lastPartial.trim().isNotEmpty()

    /**
     * Text to insert at stop. A non-blank final decode wins; otherwise the
     * last live partial is committed so the user is never left waiting on
     * an empty second pass.
     */
    fun commitText(lastPartial: String, finalText: String): String {
        val final = finalText.trim()
        if (final.isNotEmpty()) return final
        return lastPartial.trim()
    }
}

/**
 * Partial-decode window. Offline Parakeet cannot continue a stream, so we
 * refuse to re-decode a 5-minute buffer every 800ms. Short utterances still
 * decode in full. Longer ones refresh a full decode every FULL_REFRESH_SECONDS
 * and only preview the tail between refreshes.
 */
object ChunkDecodePolicy {
    const val SAMPLES_PER_SECOND = 16000
    const val WINDOW_SECONDS = 12
    const val WINDOW_SAMPLES = SAMPLES_PER_SECOND * WINDOW_SECONDS
    const val FULL_REFRESH_SECONDS = 10
    const val FULL_REFRESH_SAMPLES = SAMPLES_PER_SECOND * FULL_REFRESH_SECONDS

    data class Slice(val start: Int, val isFull: Boolean)

    fun slice(sampleCount: Int, lastFullAt: Int): Slice {
        if (sampleCount <= 0) return Slice(0, true)
        if (sampleCount <= WINDOW_SAMPLES) return Slice(0, true)
        if (sampleCount - lastFullAt >= FULL_REFRESH_SAMPLES) return Slice(0, true)
        return Slice(sampleCount - WINDOW_SAMPLES, false)
    }
}

object VoiceToAgent {
    private val PREFIX = Regex("(?i)^hey\\s+(kimi|aurora|prima)\\b[,:]?\\s*(.*)$")

    data class Command(val agentId: String, val rest: String)

    fun parse(text: String): Command? {
        val match = PREFIX.matchEntire(text.trim()) ?: return null
        return Command(agentId = match.groupValues[1].lowercase(), rest = match.groupValues[2].trim())
    }
}

