package org.futo.voiceinput.shared.types

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsPunctuationTest {

    private fun insert(transcript: String): String =
        (VoiceCommandProcessor.process(transcript) as ProcessedVoiceResult.InsertText).text

    @Test
    fun period_insertsDotAndAbsorbsSpacing() {
        assertEquals("hello. world", insert("hello period world"))
    }

    @Test
    fun period_absorbsRecognizerAppendedPunctuation() {
        assertEquals("hello. world", insert("hello period. world"))
    }

    @Test
    fun fullStop_aliasesPeriod() {
        assertEquals("hello. world", insert("hello full stop world"))
    }

    @Test
    fun comma_question_exclamation() {
        assertEquals("well, maybe? yes! go", insert("well comma maybe question mark yes exclamation mark go"))
        assertEquals("yes! go", insert("yes exclamation point go"))
    }

    @Test
    fun commandsAreCaseInsensitive() {
        assertEquals("hello. world", insert("hello Period world"))
    }

    @Test
    fun wordBoundaries_longerWordsUntouched() {
        // "periodic"/"commas" must not be eaten by the command table.
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
