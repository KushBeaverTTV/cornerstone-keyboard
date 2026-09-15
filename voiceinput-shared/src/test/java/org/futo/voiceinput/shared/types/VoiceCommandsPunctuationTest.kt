package org.futo.voiceinput.shared.types

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsPunctuationTest {

    private fun insert(transcript: String): String =
        (VoiceCommandProcessor.process(transcript) as ProcessedVoiceResult.InsertText).text

    @Test
    fun questionMark_exclamationMark_insertPunctuationAndAbsorbSpacing() {
        assertEquals("well maybe? yes! go", insert("well maybe question mark yes exclamation mark go"))
        assertEquals("yes! go", insert("yes exclamation point go"))
    }

    @Test
    fun questionMark_absorbsRecognizerAppendedPunctuation() {
        assertEquals("really? world", insert("really question mark. world"))
    }

    @Test
    fun period_passesThroughUnmodified() {
        // "period"/"full stop" are everyday words — rewriting them corrupts prose.
        assertEquals("over a period of time", insert("over a period of time"))
        assertEquals("hello period world", insert("hello period world"))
        assertEquals("a full stop is punctuation", insert("a full stop is punctuation"))
    }

    @Test
    fun comma_passesThroughUnmodified() {
        assertEquals("a comma splice", insert("a comma splice"))
        assertEquals("well comma maybe", insert("well comma maybe"))
    }

    @Test
    fun commandsAreCaseInsensitive() {
        assertEquals("hello? world", insert("hello Question Mark world"))
    }

    @Test
    fun wordBoundaries_longerWordsUntouched() {
        // "periodic"/"commas" must not be eaten by anything.
        assertEquals("periodic table", insert("periodic table"))
        assertEquals("commas matter", insert("commas matter"))
    }

    @Test
    fun adjacentCommands_collapse() {
        assertEquals("wow!\nnext", insert("wow exclamation mark new line next"))
    }

    @Test
    fun standaloneCommands_stillWork() {
        assertEquals(ProcessedVoiceResult.PerformImeAction, VoiceCommandProcessor.process("send it"))
        assertEquals(ProcessedVoiceResult.DeleteLastSentence, VoiceCommandProcessor.process("delete that"))
    }
}
