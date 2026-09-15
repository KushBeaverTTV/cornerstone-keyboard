package org.futo.voiceinput.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolicyTest {

    @Test
    fun vadPolicyShouldStop_whenUseVadIsFalse_neverStops() {
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = 201, totalSpeechFrames = 100, useVAD = false))
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = Int.MAX_VALUE, totalSpeechFrames = 100, useVAD = false))
    }

    @Test
    fun vadPolicyShouldStop_whenHasNotTalked_neverStops() {
        assertFalse(VadPolicy.shouldStop(hasTalked = false, numConsecutiveNonSpeech = 201, totalSpeechFrames = 100, useVAD = true))
        assertFalse(VadPolicy.shouldStop(hasTalked = false, numConsecutiveNonSpeech = Int.MAX_VALUE, totalSpeechFrames = 100, useVAD = true))
    }

    @Test
    fun vadPolicyShouldStop_belowMinSpeech_neverStops() {
        // Noise that trips hasTalked but with < ~1s of VAD speech must not auto-stop.
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = 201, totalSpeechFrames = 0, useVAD = true))
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = Int.MAX_VALUE, totalSpeechFrames = VadPolicy.MIN_SPEECH_FRAMES_TO_AUTOSTOP - 1, useVAD = true))
    }

    @Test
    fun vadPolicyShouldStop_atThreshold_doesNotStop() {
        assertFalse(VadPolicy.shouldStop(
            hasTalked = true,
            numConsecutiveNonSpeech = VadPolicy.STOP_CONSECUTIVE_NON_SPEECH_FRAMES,
            totalSpeechFrames = 100,
            useVAD = true,
        ))
    }

    @Test
    fun vadPolicyShouldStop_aboveThresholdWithMinSpeech_stops() {
        assertTrue(VadPolicy.shouldStop(
            hasTalked = true,
            numConsecutiveNonSpeech = VadPolicy.STOP_CONSECUTIVE_NON_SPEECH_FRAMES + 1,
            totalSpeechFrames = VadPolicy.MIN_SPEECH_FRAMES_TO_AUTOSTOP,
            useVAD = true,
        ))
    }

    @Test
    fun vadPolicyShouldStop_zeroFrames_doesNotStop() {
        assertFalse(VadPolicy.shouldStop(hasTalked = true, numConsecutiveNonSpeech = 0, totalSpeechFrames = 100, useVAD = true))
    }

    @Test
    fun vadPolicyConstants_arePreserved() {
        // ~3.0s of silence ends a note; countdown shows for the last ~2s.
        assertEquals(100, VadPolicy.STOP_CONSECUTIVE_NON_SPEECH_FRAMES)
        assertEquals(34, VadPolicy.COUNTDOWN_START_NON_SPEECH_FRAMES)
        assertEquals(33, VadPolicy.MIN_SPEECH_FRAMES_TO_AUTOSTOP)
        assertEquals(0.6f, VadPolicy.START_SOUND_PASS_SECONDS, 0.0f)
        assertEquals(0.004f, VadPolicy.TALK_RMS_THRESHOLD, 0.0f)
        assertEquals(8, VadPolicy.TALK_CONSECUTIVE_SPEECH_FRAMES)
    }

    @Test
    fun vadPolicyCountdown_hiddenUntilSpeechAndGracePeriod() {
        assertEquals(null, VadPolicy.countdownSeconds(false, 100, 100, true))
        assertEquals(null, VadPolicy.countdownSeconds(true, 33, 100, true))
        assertEquals(null, VadPolicy.countdownSeconds(true, 100, 32, true))
        assertEquals(null, VadPolicy.countdownSeconds(true, 100, 100, false))
    }

    @Test
    fun vadPolicyCountdown_reportsTwoOneAndClearsOnSpeech() {
        assertEquals(2, VadPolicy.countdownSeconds(true, 34, 100, true))
        assertEquals(1, VadPolicy.countdownSeconds(true, 67, 100, true))
        assertEquals(1, VadPolicy.countdownSeconds(true, 100, 100, true))
        assertEquals(null, VadPolicy.countdownSeconds(true, 0, 101, true))
    }

    @Test
    fun vadPolicy_stopsAfterAboutThreeSecondsOfSilence() {
        // 100 frames * 30ms = 3.0s. Regression net against drifting back to
        // the 4.2s gate that read as lag once the commit chain stopped blocking.
        assertEquals(3000, VadPolicy.STOP_CONSECUTIVE_NON_SPEECH_FRAMES * 30)
    }

    @Test
    fun commitFirst_isTheContract() {
        assertTrue(CommitFirstPolicy.COMMIT_BEFORE_POLISH)
    }

    @Test
    fun commitFirst_replacementDeleteCount_swapsExactCommittedSuffix() {
        assertEquals(
            11,
            CommitFirstPolicy.replacementDeleteCount(
                beforeCursor = "Earlier text. hello world",
                committed = "hello world",
                corrected = "Hello, world.",
            ),
        )
    }

    @Test
    fun commitFirst_replacementDeleteCount_countsCodePointsNotChars() {
        val committed = "ok \uD83D\uDE00" // "ok " + one emoji (2 chars, 1 code point)
        assertEquals(
            4,
            CommitFirstPolicy.replacementDeleteCount(
                beforeCursor = committed,
                committed = committed,
                corrected = "OK \uD83D\uDE00",
            ),
        )
    }

    @Test
    fun commitFirst_replacementDeleteCount_abortsWhenFieldMovedOn() {
        // User kept typing after dictation: never touch what they typed.
        assertEquals(
            null,
            CommitFirstPolicy.replacementDeleteCount(
                beforeCursor = "hello world and more typing",
                committed = "hello world",
                corrected = "Hello, world.",
            ),
        )
    }

    @Test
    fun commitFirst_replacementDeleteCount_abortsOnNoopOrBlank() {
        assertEquals(null, CommitFirstPolicy.replacementDeleteCount("hello", "hello", "hello"))
        assertEquals(null, CommitFirstPolicy.replacementDeleteCount("hello", "hello", "  hello  "))
        assertEquals(null, CommitFirstPolicy.replacementDeleteCount("hello", "hello", ""))
        assertEquals(null, CommitFirstPolicy.replacementDeleteCount("hello", "", "Hello"))
        assertEquals(null, CommitFirstPolicy.replacementDeleteCount("   ", "   ", "Hello"))
    }

    @Test
    fun engineRetention_keyboardHideIsNotMemoryPressure() {
        // Regression: TRIM_MEMORY_UI_HIDDEN fires on every keyboard dismissal and
        // used to evict the ~600MB Parakeet engine — every mic tap cold-loaded.
        assertFalse(EngineRetentionPolicy.shouldReleaseEngines(EngineRetentionPolicy.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(EngineRetentionPolicy.shouldReleaseEngines(EngineRetentionPolicy.TRIM_MEMORY_BACKGROUND))
        assertFalse(EngineRetentionPolicy.shouldReleaseEngines(5))  // RUNNING_MODERATE
        assertFalse(EngineRetentionPolicy.shouldReleaseEngines(10)) // RUNNING_LOW
    }

    @Test
    fun engineRetention_realPressureReleases() {
        assertTrue(EngineRetentionPolicy.shouldReleaseEngines(EngineRetentionPolicy.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(EngineRetentionPolicy.shouldReleaseEngines(EngineRetentionPolicy.TRIM_MEMORY_MODERATE))
        assertTrue(EngineRetentionPolicy.shouldReleaseEngines(80)) // COMPLETE
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
        assertEquals(5 * 60, BufferGrowth.MAX_BUFFER_SECONDS)
        assertEquals(5 * 60 * 16000, BufferGrowth.MAX_CAPACITY)
    }

    @Test
    fun bufferGrowthIsAtCap_atMaxCapacity_returnsTrue() {
        assertTrue(BufferGrowth.isAtCap(BufferGrowth.MAX_CAPACITY))
    }

    @Test
    fun bufferGrowthIsAtCap_aboveMaxCapacity_returnsTrue() {
        assertTrue(BufferGrowth.isAtCap(BufferGrowth.MAX_CAPACITY + 1))
    }

    @Test
    fun bufferGrowthIsAtCap_belowMaxCapacity_returnsFalse() {
        assertFalse(BufferGrowth.isAtCap(BufferGrowth.MAX_CAPACITY - 1))
        assertFalse(BufferGrowth.isAtCap(BufferGrowth.SAMPLES_PER_SECOND * 60))
        assertFalse(BufferGrowth.isAtCap(0))
    }

    @Test
    fun bufferGrowthNextCapacity_atCap_wouldExceedMaxCapacity() {
        // The 30-second growth step would push past the 5-minute cap, so the
        // recognizer treats the cap as reached and finishes instead.
        assertTrue(BufferGrowth.nextCapacity(BufferGrowth.MAX_CAPACITY) > BufferGrowth.MAX_CAPACITY)
    }
    @Test
    fun audioBufferPolicy_reusesWarmBufferAtPositionZero() {
        val warmCapacity = BufferGrowth.SAMPLES_PER_SECOND * BufferGrowth.EXPAND_SECONDS
        val buffer = java.nio.FloatBuffer.allocate(warmCapacity)
        buffer.put(floatArrayOf(1f, 2f, 3f))

        val reset = AudioBufferPolicy.reset(buffer)

        assertSame(buffer, reset)
        assertEquals(0, reset.position())
        assertEquals(warmCapacity, reset.capacity())
    }

    @Test
    fun audioBufferPolicy_shrinksExpandedBufferToFreshWarmCapacity() {
        val warmCapacity = BufferGrowth.SAMPLES_PER_SECOND * BufferGrowth.EXPAND_SECONDS
        val expanded = java.nio.FloatBuffer.allocate(warmCapacity * 2)
        expanded.put(1f)

        val reset = AudioBufferPolicy.reset(expanded)

        assertNotSame(expanded, reset)
        assertEquals(0, reset.position())
        assertEquals(warmCapacity, reset.capacity())
    }

    @Test
    fun finishPolicy_showsInlinePartials() {
        assertTrue(FinishPolicy.SHOW_INLINE_PARTIALS)
    }

    @Test
    fun finishPolicy_hidesPinwheelWhenPartialExists() {
        assertFalse(FinishPolicy.shouldShowPinwheel(hasUsablePartial = true))
    }

    @Test
    fun finishPolicy_showsPinwheelWhenNoPartial() {
        assertTrue(FinishPolicy.shouldShowPinwheel(hasUsablePartial = false))
    }

    @Test
    fun finishPolicy_finalDecodingMethod_isGreedy() {
        assertEquals("greedy_search", FinishPolicy.FINAL_DECODING_METHOD)
    }

    @Test
    fun polishPolicy_shouldAttempt_requiresOptIn() {
        assertFalse(PolishPolicy.shouldAttempt(enabled = false, offlineText = "hello world"))
    }

    @Test
    fun polishPolicy_shouldAttempt_rejectsBlankText() {
        assertFalse(PolishPolicy.shouldAttempt(enabled = true, offlineText = ""))
        assertFalse(PolishPolicy.shouldAttempt(enabled = true, offlineText = "   "))
    }

    @Test
    fun polishPolicy_shouldAttempt_optedInWithText() {
        assertTrue(PolishPolicy.shouldAttempt(enabled = true, offlineText = "hello world"))
    }

    @Test
    fun polishPolicy_allowedInField_blocksPasswordAndIncognito() {
        // Privacy: polish ships the transcript to the agent server.
        assertFalse(PolishPolicy.allowedInField(isPassword = true, isIncognito = false))
        assertFalse(PolishPolicy.allowedInField(isPassword = false, isIncognito = true))
        assertFalse(PolishPolicy.allowedInField(isPassword = true, isIncognito = true))
        assertTrue(PolishPolicy.allowedInField(isPassword = false, isIncognito = false))
    }

    @Test
    fun dictationPolishPolicy_onDeviceDoesNotDependOnOnlineOptIn() {
        assertTrue(
            DictationPolishPolicy.shouldAttempt(
                onDeviceEnabled = true,
                onlineEnabled = false,
                offlineText = "restore this punctuation",
            )
        )
    }

    @Test
    fun dictationPolishPolicy_requiresASelectedRouteAndNonBlankText() {
        assertFalse(
            DictationPolishPolicy.shouldAttempt(
                onDeviceEnabled = false,
                onlineEnabled = false,
                offlineText = "restore this punctuation",
            )
        )
        assertFalse(
            DictationPolishPolicy.shouldAttempt(
                onDeviceEnabled = true,
                onlineEnabled = false,
                offlineText = "   ",
            )
        )
        assertTrue(
            DictationPolishPolicy.shouldAttempt(
                onDeviceEnabled = false,
                onlineEnabled = true,
                offlineText = "restore this punctuation",
            )
        )
    }

    @Test
    fun finishPolicy_commitText_prefersLastPartialWhenFinalBlank() {
        assertEquals("hello world", FinishPolicy.commitText(lastPartial = "hello world", finalText = ""))
        assertEquals("hello world", FinishPolicy.commitText(lastPartial = "hello world", finalText = "   "))
    }

    @Test
    fun finishPolicy_commitText_prefersFinalWhenPresent() {
        assertEquals("final cut", FinishPolicy.commitText(lastPartial = "hello", finalText = "final cut"))
    }

    @Test
    fun finishPolicy_commitText_blankWhenNothingAvailable() {
        assertEquals("", FinishPolicy.commitText(lastPartial = "", finalText = ""))
        assertEquals("", FinishPolicy.commitText(lastPartial = "   ", finalText = "\n"))
    }

    @Test
    fun editorPublication_livePartialNeverEntersTargetField() {
        // Regression: Telegram can lose Android's composing span. Streaming a
        // live partial into the field and then committing the final transcript
        // produced "partial + final" — an exact duplicate of the spoken text.
        assertFalse(EditorPublicationPolicy.STREAM_PARTIALS_TO_EDITOR)
        assertEquals(
            null,
            EditorPublicationPolicy.partialTextForEditor(
                text = "Okay, then me and you work on the keyboard here.",
                finalizing = false,
            ),
        )
    }

    @Test
    fun editorPublication_finalizingRejectsLatePartial() {
        assertEquals(
            null,
            EditorPublicationPolicy.partialTextForEditor(
                text = "stale partial",
                finalizing = true,
            ),
        )
    }

    @Test
    fun finishPolicy_shouldCommitPartialImmediately_whenPartialExists() {
        assertTrue(FinishPolicy.shouldCommitPartialImmediately(lastPartial = "hello"))
        assertFalse(FinishPolicy.shouldCommitPartialImmediately(lastPartial = ""))
        assertFalse(FinishPolicy.shouldCommitPartialImmediately(lastPartial = "  "))
    }

    @Test
    fun partialDecodeGate_allowsOnlyOneJobAndRejectsStartsAfterStop() {
        val gate = PartialDecodeGate()

        val session = gate.startSession()
        assertTrue(gate.tryStart(session))
        assertFalse(gate.tryStart(session))

        gate.stopSession(session)
        assertFalse(gate.tryStart(session))

        gate.finished(session)
        assertFalse(gate.tryStart(session))

        val nextSession = gate.startSession()
        assertTrue(gate.tryStart(nextSession))
        gate.finished(nextSession)
    }

    @Test
    fun partialDecodeGate_stoppedSessionCannotPublishReturningPartial() {
        val gate = PartialDecodeGate()
        val session = gate.startSession()
        assertTrue(gate.tryStart(session))

        gate.stopSession(session)
        var published = false
        assertFalse(gate.runIfCurrent(session) { published = true })
        assertFalse(published)
        gate.finished(session)
    }

    @Test
    fun partialDecodeGate_oldSessionCannotOwnOrMutateNewSession() {
        val gate = PartialDecodeGate()

        val oldSession = gate.startSession()
        assertTrue(gate.tryStart(oldSession))

        val newSession = gate.startSession()
        assertFalse(gate.isCurrent(oldSession))
        assertTrue(gate.isCurrent(newSession))
        var oldSessionMutatedState = false
        assertFalse(gate.runIfCurrent(oldSession) { oldSessionMutatedState = true })
        assertFalse(oldSessionMutatedState)
        assertTrue(gate.runIfCurrent(newSession) { })
        assertFalse(gate.tryStart(newSession))

        gate.finished(oldSession)
        assertTrue(gate.tryStart(newSession))
        gate.finished(newSession)
    }

    @Test
    fun incrementalDecode_completedBlankPartialAdvancesCoverageWithoutInventingText() {
        val completion = IncrementalDecodePolicy.completedPartial(
            base = "already spoken",
            tail = "",
            sampleCount = 192000,
            promote = true
        )

        assertEquals("already spoken", completion.joinedTranscript)
        assertEquals(192000, completion.deliveredAtSamples)
        assertEquals("already spoken", completion.promotedBaseTranscript)
        assertEquals(192000, completion.promotedBaseSamples)
    }

    @Test
    fun incrementalDecode_tailStart_insufficientNewAudio_isNull() {
        // Less than 1s of new audio past the checkpoint: not worth a decode.
        assertEquals(null, IncrementalDecodePolicy.tailStart(sampleCount = 8000, baseSamples = 0))
        assertEquals(null, IncrementalDecodePolicy.tailStart(sampleCount = 100000, baseSamples = 99500))
    }

    @Test
    fun incrementalDecode_tailStart_startsAtCheckpoint() {
        // The tail slice starts exactly at the checkpoint: base and tail cover
        // disjoint audio, so joined text can never duplicate content.
        assertEquals(0, IncrementalDecodePolicy.tailStart(sampleCount = 32000, baseSamples = 0))
        assertEquals(160000, IncrementalDecodePolicy.tailStart(sampleCount = 200000, baseSamples = 160000))
    }

    @Test
    fun incrementalDecode_shouldPromote_onlyAfterPromoteSeconds() {
        assertFalse(IncrementalDecodePolicy.shouldPromote(sampleCount = IncrementalDecodePolicy.PROMOTE_SAMPLES - 1, baseSamples = 0))
        assertTrue(IncrementalDecodePolicy.shouldPromote(sampleCount = IncrementalDecodePolicy.PROMOTE_SAMPLES, baseSamples = 0))
        assertFalse(IncrementalDecodePolicy.shouldPromote(sampleCount = IncrementalDecodePolicy.PROMOTE_SAMPLES + 100, baseSamples = 200))
    }

    @Test
    fun incrementalDecode_needsFinalTail_onlyForOneSecondOrMore() {
        assertFalse(IncrementalDecodePolicy.needsFinalTail(0))
        assertFalse(IncrementalDecodePolicy.needsFinalTail(15999))
        assertTrue(IncrementalDecodePolicy.needsFinalTail(16000))
    }

    @Test
    fun incrementalDecode_pendingUndecoded_measuresFromDelivery() {
        assertEquals(0, IncrementalDecodePolicy.pendingUndecoded(endSamples = 100000, lastDeliveredSamples = 100000))
        assertEquals(8000, IncrementalDecodePolicy.pendingUndecoded(endSamples = 100000, lastDeliveredSamples = 92000))
        // Delivery past the end (e.g. after trailing-noise trim) clamps to 0.
        assertEquals(0, IncrementalDecodePolicy.pendingUndecoded(endSamples = 90000, lastDeliveredSamples = 100000))
    }

    @Test
    fun incrementalDecode_pendingUndecoded_skippedDecodeStaysPending() {
        // Regression (review finding): a partial that was LAUNCHED but never
        // delivered (busy-gate skip / cancelled in flight) must still count as
        // undecoded audio at stop — otherwise the early-commit path silently
        // drops the user's final words.
        val end = 100000
        val lastDelivered = end - 20000 // 1.25s of speech never decoded
        val pending = IncrementalDecodePolicy.pendingUndecoded(end, lastDelivered)
        assertTrue(IncrementalDecodePolicy.needsFinalTail(pending))
    }

    @Test
    fun incrementalDecode_finalStart_zeroWithoutBase_checkpointOtherwise() {
        assertEquals(0, IncrementalDecodePolicy.finalStart(baseTranscript = "", baseSamples = 160000, endSamples = 200000))
        assertEquals(0, IncrementalDecodePolicy.finalStart(baseTranscript = "   ", baseSamples = 160000, endSamples = 200000))
        assertEquals(160000, IncrementalDecodePolicy.finalStart(baseTranscript = "hello", baseSamples = 160000, endSamples = 200000))
        // Trim may cut the end below the checkpoint: start clamps to end.
        assertEquals(150000, IncrementalDecodePolicy.finalStart(baseTranscript = "hello", baseSamples = 160000, endSamples = 150000))
    }

    @Test
    fun incrementalDecode_join_handlesBlanks() {
        assertEquals("tail", IncrementalDecodePolicy.join("", "tail"))
        assertEquals("tail", IncrementalDecodePolicy.join("   ", "tail"))
        assertEquals("base", IncrementalDecodePolicy.join("base", ""))
        assertEquals("base", IncrementalDecodePolicy.join("base", "  "))
        assertEquals("", IncrementalDecodePolicy.join("", ""))
    }

    @Test
    fun incrementalDecode_join_concatenatesWithSingleSpace() {
        assertEquals("hello world again", IncrementalDecodePolicy.join("hello world ", " again"))
    }

    @Test
    fun incrementalDecode_join_preservesAmbiguousBoundaryUntilFullDecode() {
        assertEquals(
            "I could set up an AI tool now. And then use it right after",
            IncrementalDecodePolicy.join(
                "I could set up an AI tool now.",
                "And then use it right after"
            )
        )
    }

    @Test
    fun incrementalDecode_detectsCaseInsensitiveContinuationMarkers() {
        listOf("and", "Then", "because", "However", "However,").forEach { marker ->
            assertTrue(
                IncrementalDecodePolicy.requiresFullReconciliation(
                    "We reached a decode checkpoint.",
                    "$marker the thought continued"
                )
            )
        }

        listOf(
            "We reached a decode checkpoint.",
            "Did we reach a decode checkpoint?",
            "We reached a decode checkpoint!"
        ).forEach { base ->
            assertTrue(
                IncrementalDecodePolicy.requiresFullReconciliation(
                    base,
                    "However, the thought continued"
                )
            )
        }
    }

    @Test
    fun incrementalDecode_ambiguousCheckpointForcesFullContextFinalDecode() {
        assertTrue(
            IncrementalDecodePolicy.requiresFullReconciliation(
                "I could set up an AI tool now.",
                "And then use it right after"
            )
        )
        assertEquals(
            0,
            IncrementalDecodePolicy.finalStart(
                baseTranscript = "I could set up an AI tool now and then use it right after",
                baseSamples = 160000,
                endSamples = 220000,
                requiresFullReconciliation = true
            )
        )
    }

    @Test
    fun trailingTrim_noSpeechTracked_keepsEverything() {
        assertEquals(100000, TrailingTrimPolicy.keptSamples(totalSamples = 100000, lastSpeechSamples = 0))
        assertEquals(100000, TrailingTrimPolicy.keptSamples(totalSamples = 100000, lastSpeechSamples = -5))
    }

    @Test
    fun trailingTrim_dropsNoisePastPad() {
        // 6s of trailing noise: keep only 0.5s of padding past the last speech.
        val total = 10 * 16000
        val lastSpeech = 4 * 16000
        assertEquals(lastSpeech + 8000, TrailingTrimPolicy.keptSamples(total, lastSpeech))
    }

    @Test
    fun trailingTrim_neverTrimsIntoSpeech() {
        val total = 10 * 16000
        // Speech right up to the end: nothing to trim.
        assertEquals(total, TrailingTrimPolicy.keptSamples(total, lastSpeechSamples = total - 100))
        assertEquals(total, TrailingTrimPolicy.keptSamples(total, lastSpeechSamples = total))
    }

    @Test
    fun partialView_ticksBeforeFirstPartial_stayHidden() {
        var state = PartialViewPolicy.BoxState.Hidden
        repeat(5) { state = PartialViewPolicy.onMagnitudeTick(state) }
        assertEquals(PartialViewPolicy.BoxState.Hidden, state)
    }

    @Test
    fun partialView_onceShown_neverHiddenExceptFinish() {
        // Regression: the partial box flashed on/off because ~10x/second
        // magnitude ticks flipped the view away between decode deliveries.
        // The delivered view sequence must be monotonic: non-blank partial
        // shows the box, and NOTHING but finish hides it again.
        var state = PartialViewPolicy.BoxState.Hidden

        state = PartialViewPolicy.onPartialResult(state, "hello")
        assertEquals(PartialViewPolicy.BoxState.ShowingPartial, state)

        // A burst of magnitude ticks (the recording loop's rhythm) must not hide it.
        repeat(20) {
            state = PartialViewPolicy.onMagnitudeTick(state)
            assertEquals(PartialViewPolicy.BoxState.ShowingPartial, state)
        }

        // Blank/whitespace partial deliveries must not hide it either
        // (never non-blank -> blank -> non-blank outside finish).
        state = PartialViewPolicy.onPartialResult(state, "")
        assertEquals(PartialViewPolicy.BoxState.ShowingPartial, state)
        state = PartialViewPolicy.onPartialResult(state, "   ")
        assertEquals(PartialViewPolicy.BoxState.ShowingPartial, state)

        // New text updates in place — still showing.
        state = PartialViewPolicy.onPartialResult(state, "hello world")
        assertEquals(PartialViewPolicy.BoxState.ShowingPartial, state)

        // Only finish/cancel hides the box.
        state = PartialViewPolicy.onFinish()
        assertEquals(PartialViewPolicy.BoxState.Hidden, state)
    }

    @Test
    fun voiceToAgent_parsesHeyKaori() {
        val cmd = VoiceToAgent.parse("hey Kaori improve this draft")
        assertEquals("kaori", cmd!!.agentId)
        assertEquals("improve this draft", cmd.rest)
    }

    @Test
    fun voiceToAgent_recognizerSpellingsOfKaoriAllRouteToHer() {
        // Parakeet hears "Kaori" several ways; none of them may fall through
        // to plain dictation text.
        for (spoken in listOf("Kaori", "kaory", "Kauri", "kori", "Kory", "cory", "Corey")) {
            val cmd = VoiceToAgent.parse("hey $spoken summarize this")
            assertEquals(spoken, "kaori", cmd?.agentId)
            assertEquals("summarize this", cmd?.rest)
        }
    }

    @Test
    fun voiceToAgent_retiredHubNamesAliasToKaori() {
        // "Hey Kimi" habits keep working; the bridge only has Kaori now.
        assertEquals("kaori", VoiceToAgent.parse("hey kimi improve this draft")!!.agentId)
        assertEquals("kaori", VoiceToAgent.parse("Hey Aurora")!!.agentId)
        assertEquals("kaori", VoiceToAgent.parse("hey prima ?")!!.agentId)
    }

    @Test
    fun voiceToAgent_switchOnly() {
        val cmd = VoiceToAgent.parse("Hey Kaori")
        assertEquals("kaori", cmd!!.agentId)
        assertEquals("", cmd.rest)
    }

    @Test
    fun voiceToAgent_ignoresNormalSpeech() {
        assertEquals(null, VoiceToAgent.parse("hey there how are you"))
        // Word boundary: "hey koriander" is not a wake phrase.
        assertEquals(null, VoiceToAgent.parse("hey koriander is a herb"))
    }

    @Test
    fun voiceToAgent_stripsTrailingPunctuationFromRest() {
        // Regression: "Hey Kaori." used to send "." to the agent.
        val cmd = VoiceToAgent.parse("Hey Kaori.")
        assertEquals("kaori", cmd!!.agentId)
        assertEquals("", cmd.rest)
    }

    @Test
    fun voiceToAgent_stripsTrailingPunctuation_keepsPrompt() {
        assertEquals("improve this draft", VoiceToAgent.parse("hey kaori improve this draft!")!!.rest)
        assertEquals("fix this, please", VoiceToAgent.parse("hey kaori fix this, please?")!!.rest)
    }

    @Test
    fun voiceToAgent_blankAfterPunctuationStrip_isBlankRest() {
        assertEquals("", VoiceToAgent.parse("Hey Kaori!")!!.rest)
        assertEquals("", VoiceToAgent.parse("hey kori ?")!!.rest)
    }
}
