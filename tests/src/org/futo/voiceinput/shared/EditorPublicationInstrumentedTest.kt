package org.futo.voiceinput.shared

import android.view.inputmethod.BaseInputConnection
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device regression for editors (including Telegram variants) that
 * lose Android's composing span and therefore append a later final commit.
 */
@RunWith(AndroidJUnit4::class)
class EditorPublicationInstrumentedTest {
    private class ComposingSpanLosingEditor(
        targetView: View,
    ) : BaseInputConnection(targetView, true) {
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
    fun telegramLikeEditorReceivesFinalTranscriptExactlyOnce() {
        val partial = "Cornerstone test phrase alpha bravo seven."
        val final = "Cornerstone test phrase alpha bravo seven. Do not duplicate this sentence."
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val editor = ComposingSpanLosingEditor(View(context))

        EditorPublicationPolicy.partialTextForEditor(
            text = partial,
            finalizing = false,
        )?.let { editor.setComposingText(it, 1) }

        editor.commitText(FinishPolicy.commitText(partial, final), 1)

        assertEquals(final, editor.inserted.toString())
    }

    @Test
    fun latePartialCannotPublishAfterFinalizationStarts() {
        assertNull(
            EditorPublicationPolicy.partialTextForEditor(
                text = "stale partial that would duplicate",
                finalizing = true,
            ),
        )
    }
}
