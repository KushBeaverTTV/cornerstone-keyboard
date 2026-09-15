package org.futo.inputmethod.latin.xlm.correction

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.uix.POLISH_DICTATION_ONLINE
import org.futo.inputmethod.latin.uix.actions.AI_AUTOCORRECT_ENABLED
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.voiceinput.shared.DictationPolishPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceCorrectionInstrumentedTest {
    @Test
    fun freshInstallDefaultsRouteDictationLocallyWithoutNetworkOptIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val onDevice = context.getSetting(ON_DEVICE_LLM_CORRECTION)
        val online = context.getSetting(POLISH_DICTATION_ONLINE)

        assertTrue("On-device correction must default on", onDevice)
        assertTrue("The Fix chip must default on", context.getSetting(AI_AUTOCORRECT_ENABLED))
        assertFalse("Network polish must remain opt-in", online)
        assertTrue(
            "Dictation must route locally without the online switch",
            DictationPolishPolicy.shouldAttempt(onDevice, online, "restore this punctuation"),
        )
    }

    @Test
    fun bundledModelLoadsAndCorrectsThroughJni() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outcome = OnDeviceCorrection.correct(
            context = context,
            raw = "do not delete the folder",
            enabled = true,
        )
        assertTrue("Expected an applied on-device correction, got $outcome", outcome is CorrectionOutcome.Applied)
        val applied = outcome as CorrectionOutcome.Applied
        assertTrue(applied.prediction.contains("not", ignoreCase = true))
        assertTrue(applied.prediction.contains("delete", ignoreCase = true))

        val started = SystemClock.elapsedRealtime()
        val timedOut = CorrectionNative.nativeGenerate(
            OnDeviceCorrection.wrapPrompt("write a very long explanation about every possible keyboard feature"),
            4096,
            1L,
        )
        val elapsed = SystemClock.elapsedRealtime() - started
        assertTrue("native timeout took ${elapsed}ms", elapsed < 2_000L)
        assertNull("timed-out output must not parse as a correction", CorrectionJson.parse(timedOut))
    }
}
