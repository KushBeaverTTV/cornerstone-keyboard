package org.futo.voiceinput.shared

import android.util.Log
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParakeetOnDeviceInstrumentedTest {
    private class ComposingSpanLosingEditor(targetView: View) : BaseInputConnection(targetView, true) {
        val inserted = StringBuilder()

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            inserted.append(text?.toString().orEmpty())
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            inserted.append(text?.toString().orEmpty())
            return true
        }
    }

    @Test
    fun provisionedParakeetDecodesKnownPhraseAndPublishesItOnce() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val rawId = testContext.resources.getIdentifier(
            "cornerstone_voice_test",
            "raw",
            testContext.packageName,
        )
        assertTrue("PCM test resource missing from instrumentation APK", rawId != 0)
        val bytes = testContext.resources.openRawResource(rawId).use { it.readBytes() }
        assertTrue("PCM resource must contain complete 16-bit samples", bytes.isNotEmpty() && bytes.size % 2 == 0)
        val samples = FloatArray(bytes.size / 2) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            (((high shl 8) or low).toShort().toInt()) / 32768.0f
        }

        assumeTrue(
            "Parakeet model is not provisioned on this target device",
            BUILTIN_PARAKEET_MODEL.exists(targetContext),
        )
        val partials = mutableListOf<String>()
        val manager = ModelManager(targetContext)
        val result = try {
            MultiModelRunner(manager).run(
                samples = samples,
                runConfiguration = MultiModelRunConfiguration(
                    primaryModel = BUILTIN_PARAKEET_MODEL,
                    languageSpecificModels = emptyMap(),
                ),
                decodingConfiguration = DecodingConfiguration(
                    glossary = emptyList(),
                    languages = emptySet(),
                    suppressSymbols = false,
                ),
                callback = object : ModelInferenceCallback {
                    override fun updateStatus(state: InferenceState) {
                        Log.i("ParakeetDeviceTest", "STATUS=$state")
                    }

                    override fun partialResult(string: String) {
                        partials += string
                        Log.i("ParakeetDeviceTest", "PARTIAL=$string")
                    }
                },
            )
        } finally {
            manager.cleanUp()
        }.trim()

        Log.i("ParakeetDeviceTest", "FINAL=$result")
        assertTrue("Parakeet returned a blank transcript", result.isNotBlank())
        val normalized = result.lowercase()
        val recognizableTokens = listOf("corner", "stone", "alpha", "bravo", "seven", "duplicate", "sentence")
            .count { it in normalized }
        assertTrue("Transcript did not contain enough known phrase tokens: $result", recognizableTokens >= 2)

        val editor = ComposingSpanLosingEditor(View(targetContext))
        partials.forEach { partial ->
            EditorPublicationPolicy.partialTextForEditor(
                text = partial,
                finalizing = true,
            )?.let { editor.setComposingText(it, 1) }
        }
        editor.commitText(FinishPolicy.commitText(partials.lastOrNull().orEmpty(), result), 1)

        assertEquals(
            "Decoded final transcript must reach a composing-span-losing editor exactly once",
            result,
            editor.inserted.toString(),
        )
    }
}
