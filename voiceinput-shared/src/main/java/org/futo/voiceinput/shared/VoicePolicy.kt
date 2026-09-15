package org.futo.voiceinput.shared

/**
 * Pure, stateless voice-recording policy decisions extracted from AudioRecognizer.
 *
 * These functions encode the currently tuned constants verbatim; they exist so
 * future changes to VAD stop behaviour, partial-decode throttling, or buffer
 * growth have a regression net.
 */

object VadPolicy {
    // 66 frames (~2s) cut people off mid-thought; 200 (~6s) felt frozen;
    // 140 (~4.2s) still read as "waiting on the keyboard" once the post-stop
    // chain stopped blocking. 100 frames (~3.0s) keeps a thinking pause and
    // matches what people expect from system dictation. The last ~2 seconds
    // are surfaced as an explicit countdown, and the stop button is always there.
    const val STOP_CONSECUTIVE_NON_SPEECH_FRAMES = 100
    const val COUNTDOWN_START_NON_SPEECH_FRAMES = 34
    private const val VAD_FRAME_MS = 30

    // Auto-stop must never fire before the user has actually said something:
    // require at least ~1s of VAD-detected speech (30ms frames) first, so
    // background noise that trips hasTalked can't end the session early.
    const val MIN_SPEECH_FRAMES_TO_AUTOSTOP = 33

    // Don't treat the start sound as user speech; gate hasTalked until 0.6s
    // of audio has been collected.
    const val START_SOUND_PASS_SECONDS = 0.6f

    // Near-field gate: this RMS level counts as speech, so quiet far-field
    // noise doesn't mark the user as having talked.
    const val TALK_RMS_THRESHOLD = 0.004f

    // Alternatively, this many consecutive VAD speech frames count as talking.
    const val TALK_CONSECUTIVE_SPEECH_FRAMES = 8

    fun shouldStop(
        hasTalked: Boolean,
        numConsecutiveNonSpeech: Int,
        totalSpeechFrames: Int,
        useVAD: Boolean,
    ): Boolean =
        hasTalked &&
            totalSpeechFrames >= MIN_SPEECH_FRAMES_TO_AUTOSTOP &&
            numConsecutiveNonSpeech > STOP_CONSECUTIVE_NON_SPEECH_FRAMES &&
            useVAD

    fun countdownSeconds(
        hasTalked: Boolean,
        numConsecutiveNonSpeech: Int,
        totalSpeechFrames: Int,
        useVAD: Boolean,
    ): Int? {
        if (!useVAD || !hasTalked ||
            totalSpeechFrames < MIN_SPEECH_FRAMES_TO_AUTOSTOP ||
            numConsecutiveNonSpeech < COUNTDOWN_START_NON_SPEECH_FRAMES
        ) return null

        val remainingFrames =
            (STOP_CONSECUTIVE_NON_SPEECH_FRAMES - numConsecutiveNonSpeech).coerceAtLeast(1)
        return ((remainingFrames * VAD_FRAME_MS + 999) / 1000).coerceAtLeast(1)
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
 * greedy-only if a final decode is still required. (Online polish never blocks
 * insertion either — that gating lives in PolishPolicy.)
 */
object FinishPolicy {
    const val SHOW_INLINE_PARTIALS = true
    const val FINAL_DECODING_METHOD = "greedy_search"

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
 * Controls what leaves the Cornerstone Voice UI while recognition is still
 * in progress. Live partials remain visible in the voice panel, but must not
 * be streamed into the target app: many Android editors implement composing
 * text inconsistently and turn the final replacement into partial + final.
 * A single final commit is the only cross-app behavior we can guarantee.
 */
object EditorPublicationPolicy {
    const val STREAM_PARTIALS_TO_EDITOR = false

    fun partialTextForEditor(text: String, finalizing: Boolean): String? =
        text.takeIf {
            STREAM_PARTIALS_TO_EDITOR && !finalizing && it.isNotBlank()
        }
}

/**
 * Online dictation polish gating. Pure so the wiring decision — "should a
 * polish attempt even start?" — is unit-testable without a Context.
 *
 * Polish is strictly opt-in and NEVER blocks insertion: callers commit the
 * offline-processed text whenever this returns false or the attempt fails.
 */
object PolishPolicy {
    fun shouldAttempt(enabled: Boolean, offlineText: String): Boolean =
        enabled && offlineText.isNotBlank()

    /**
     * Privacy gate: polish ships the transcript to the agent server, so it must
     * never run in password or incognito fields — the transcript there IS the
     * secret. Mirrors the Agent key / AI-autocorrect guard chain.
     */
    fun allowedInField(isPassword: Boolean, isIncognito: Boolean): Boolean =
        !isPassword && !isIncognito
}

/**
 * Routes final dictation through either the local model or the explicitly
 * enabled network fallback. Local correction must never depend on the online
 * opt-in switch.
 */
object DictationPolishPolicy {
    fun shouldAttempt(onDeviceEnabled: Boolean, onlineEnabled: Boolean, offlineText: String): Boolean =
        offlineText.isNotBlank() && (onDeviceEnabled || onlineEnabled)
}

/**
 * Commit-first contract for dictation. The offline transcript lands in the
 * field the moment recognition ends; any correction pass (on-device Qwen or
 * online polish) runs AFTERWARDS in the background and, if it changed
 * anything, swaps the text in place. The user never waits on a language model
 * to see their words — this is the single largest source of the "stop → text
 * appears" lag the bubble app never had, since it pastes immediately.
 */
object CommitFirstPolicy {
    const val COMMIT_BEFORE_POLISH = true

    /**
     * Code points to delete before typing [corrected] over [committed], or
     * null when the swap must not happen: nothing committed, correction is a
     * no-op, or the field no longer ends with exactly what we committed (the
     * user kept typing / moved the cursor / another app touched the field).
     */
    fun replacementDeleteCount(beforeCursor: String, committed: String, corrected: String): Int? {
        if (committed.isEmpty() || committed.isBlank()) return null
        val next = corrected.trim()
        if (next.isEmpty() || next == committed.trim()) return null
        if (!beforeCursor.endsWith(committed)) return null
        return committed.codePointCount(0, committed.length)
    }
}

/**
 * When the keep-warm Parakeet engine may be dropped. Android delivers
 * TRIM_MEMORY_UI_HIDDEN (20) every time the keyboard is dismissed; treating
 * that as pressure evicted the ~600MB engine after every field and forced a
 * multi-second cold load on the next mic tap. Only genuine pressure counts.
 */
object EngineRetentionPolicy {
    // android.content.ComponentCallbacks2 constants, mirrored so the policy is JVM-testable.
    const val TRIM_MEMORY_RUNNING_CRITICAL = 15
    const val TRIM_MEMORY_UI_HIDDEN = 20
    const val TRIM_MEMORY_BACKGROUND = 40
    const val TRIM_MEMORY_MODERATE = 60

    fun shouldReleaseEngines(trimLevel: Int): Boolean =
        trimLevel == TRIM_MEMORY_RUNNING_CRITICAL || trimLevel >= TRIM_MEMORY_MODERATE
}

class PartialDecodeGate {
    private var generation = 0L
    private var acceptingStarts = false
    private var runningGeneration: Long? = null

    @Synchronized
    fun startSession(): Long {
        generation += 1
        acceptingStarts = true
        return generation
    }

    @Synchronized
    fun stopSession(session: Long) {
        if (session == generation) acceptingStarts = false
    }

    @Synchronized
    fun tryStart(session: Long): Boolean {
        if (!acceptingStarts || session != generation || runningGeneration != null) return false
        runningGeneration = session
        return true
    }

    @Synchronized
    fun finished(session: Long) {
        if (runningGeneration == session) runningGeneration = null
    }

    @Synchronized
    fun isCurrent(session: Long): Boolean = session == generation

    @Synchronized
    fun runIfCurrent(session: Long, action: () -> Unit): Boolean {
        if (!acceptingStarts || session != generation) return false
        action()
        return true
    }
}

/**
 * Incremental partial-decode window (Parakeet path). sherpa's OfflineRecognizer
 * cannot continue a stream, and re-decoding the whole buffer every tick made
 * decode cost grow linearly with note length on the single inference thread —
 * long notes froze live text and queued the final decode behind stale work.
 *
 * Instead, partials decode ONLY the audio recorded since the last promoted
 * checkpoint and the result is joined onto the checkpoint text. Every decode
 * is O(tail), the live transcript is always complete, and promotion folds the
 * tail into the base for free (no decode). Because the tail slice starts
 * exactly at the checkpoint, joined text can never contain duplicated audio.
 */
object IncrementalDecodePolicy {
    data class Completion(
        val joinedTranscript: String,
        val deliveredAtSamples: Int,
        val promotedBaseTranscript: String?,
        val promotedBaseSamples: Int?
    )

    const val SAMPLES_PER_SECOND = 16_000

    // Don't burn a decode on sub-second tails — they only produce noise.
    const val MIN_TAIL_SAMPLES = SAMPLES_PER_SECOND

    // Once the undecoded tail is this long, a successful tail decode is folded
    // into the base transcript (checkpoint promotion) and the tail restarts.
    const val PROMOTE_SECONDS = 10
    const val PROMOTE_SAMPLES = SAMPLES_PER_SECOND * PROMOTE_SECONDS

    // At stop, audio not yet covered by any COMPLETED partial decode below this
    // size is not worth a final tail decode — commit the live transcript
    // immediately. Measured via [pendingUndecoded] from the last delivered
    // (completed) decode position, never the last launch.
    const val FINAL_TAIL_MIN_SAMPLES = SAMPLES_PER_SECOND

    /** Start index for a partial decode, or null when there isn't enough new audio. */
    fun tailStart(sampleCount: Int, baseSamples: Int): Int? {
        if (sampleCount - baseSamples < MIN_TAIL_SAMPLES) return null
        return baseSamples
    }

    /**
     * Samples of audio not covered by any completed decode: the distance from
     * the last DELIVERED (completed) decode position to the buffer end. Clamped
     * at 0 — a delivery past the end (e.g. after trimming) means nothing pending.
     */
    fun pendingUndecoded(endSamples: Int, lastDeliveredSamples: Int): Int =
        maxOf(0, endSamples - lastDeliveredSamples)

    fun shouldPromote(sampleCount: Int, baseSamples: Int): Boolean =
        sampleCount - baseSamples >= PROMOTE_SAMPLES

    /** True when enough audio arrived since the last partial to justify a final tail decode at stop. */
    fun needsFinalTail(pendingSamples: Int): Boolean =
        pendingSamples >= FINAL_TAIL_MIN_SAMPLES

    private val continuationAtSeam = Regex(
        "^(and|but|or|so|yet|then|because|however)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * A punctuated checkpoint followed by a continuation is ambiguous because
     * each side was decoded independently. Finalization must decode the full
     * utterance instead of trusting punctuation inferred at that audio seam.
     */
    fun requiresFullReconciliation(base: String, tail: String): Boolean {
        val terminal = base.trim().lastOrNull()
        return terminal in setOf('.', '?', '!') && continuationAtSeam.containsMatchIn(tail.trim())
    }

    /** Where a final decode should start: 0 unless a trustworthy checkpoint base exists. */
    fun finalStart(
        baseTranscript: String,
        baseSamples: Int,
        endSamples: Int,
        requiresFullReconciliation: Boolean = false
    ): Int =
        if (baseTranscript.isBlank() || requiresFullReconciliation) 0 else baseSamples.coerceAtMost(endSamples)

    fun completedPartial(
        base: String,
        tail: String,
        sampleCount: Int,
        promote: Boolean
    ): Completion {
        val joined = join(base, tail)
        return Completion(
            joinedTranscript = joined,
            deliveredAtSamples = sampleCount,
            promotedBaseTranscript = joined.takeIf { promote },
            promotedBaseSamples = sampleCount.takeIf { promote }
        )
    }

    /**
     * Joins checkpoint text with freshly decoded tail text. The two cover
     * disjoint audio by construction, so joining can never duplicate content.
     */
    fun join(base: String, tail: String): String {
        val b = base.trim()
        val t = tail.trim()
        if (b.isEmpty()) return t
        if (t.isEmpty()) return b

        return "$b $t"
    }
}

/**
 * Display contract for the live partial box (RecognizerView).
 *
 * Once non-blank partial text is on screen it must NEVER be hidden by anything
 * except finish/cancel. Magnitude/status ticks arrive ~10x/second from the
 * recording loop and used to flip the view back to the mic bubble every
 * ~100ms — the partial box visibly flashed on and off between decode ticks.
 * A view driven by these transitions is monotonic from the user's perspective:
 * text appears, then only ever updates in place.
 */
object PartialViewPolicy {
    enum class BoxState { Hidden, ShowingPartial }

    /** A non-blank partial shows the box; blank results never change visibility. */
    fun onPartialResult(state: BoxState, text: String): BoxState =
        if (text.isNotBlank()) BoxState.ShowingPartial else state

    /** Magnitude/status ticks never change box visibility. */
    fun onMagnitudeTick(state: BoxState): BoxState = state

    /** Finish/cancel/processing hides the box. */
    fun onFinish(): BoxState = BoxState.Hidden
}

/**
 * Trailing non-speech trim. Recording that outlives the user's last word feeds
 * pure noise to the final decode — and a transducer decoding noise is what
 * produces garbage and repetition-loop text. The recorder tracks the buffer
 * position of the last VAD speech frame; at stop, everything past it (plus a
 * small padding so final word offsets aren't clipped) is dropped from the
 * decode buffer. Applies only when VAD tracked speech; otherwise no trim.
 */
object TrailingTrimPolicy {
    const val PAD_SECONDS = 0.5f

    /** Buffer position to decode up to. [lastSpeechSamples] <= 0 means "no speech tracked — keep everything". */
    fun keptSamples(totalSamples: Int, lastSpeechSamples: Int, sampleRate: Int = IncrementalDecodePolicy.SAMPLES_PER_SECOND): Int {
        if (lastSpeechSamples <= 0) return totalSamples
        val keep = lastSpeechSamples + (PAD_SECONDS * sampleRate).toInt()
        return minOf(totalSamples, keep)
    }
}

object VoiceToAgent {
    const val DEFAULT_AGENT_ID = "kaori"

    /**
     * Spoken names the wake phrase accepts, mapped to the agent id they route
     * to. Kaori is the one active agent; the recognizer hears her name in
     * several spellings, and the retired hub names still work as aliases so
     * old habits keep landing somewhere useful.
     */
    val NAME_ALIASES: Map<String, String> = mapOf(
        "kaori" to DEFAULT_AGENT_ID,
        "kaory" to DEFAULT_AGENT_ID,
        "kauri" to DEFAULT_AGENT_ID,
        "kori" to DEFAULT_AGENT_ID,
        "kory" to DEFAULT_AGENT_ID,
        "cory" to DEFAULT_AGENT_ID,
        "corey" to DEFAULT_AGENT_ID,
        "kimi" to DEFAULT_AGENT_ID,
        "aurora" to DEFAULT_AGENT_ID,
        "prima" to DEFAULT_AGENT_ID,
    )

    private val PREFIX = Regex(
        "(?i)^hey\\s+(" + NAME_ALIASES.keys.joinToString("|") { Regex.escape(it) } + ")\\b[,:]?\\s*(.*)$"
    )

    data class Command(val agentId: String, val rest: String)

    fun parse(text: String): Command? {
        val match = PREFIX.matchEntire(text.trim()) ?: return null
        // The recognizer appends sentence punctuation to everything, including
        // the command itself ("Hey Kaori." -> rest "."). Strip trailing sentence
        // punctuation so it never becomes the agent prompt — same normalization
        // as VoiceCommandProcessor's standalone-command matching.
        val rest = match.groupValues[2].trim().trimEnd('.', '!', '?', ',').trim()
        val spoken = match.groupValues[1].lowercase()
        return Command(agentId = NAME_ALIASES[spoken] ?: DEFAULT_AGENT_ID, rest = rest)
    }
}

