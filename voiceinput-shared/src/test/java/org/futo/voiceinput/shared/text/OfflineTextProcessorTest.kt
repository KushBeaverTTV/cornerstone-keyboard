package org.futo.voiceinput.shared.text

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineTextProcessorTest {

    @Test
    fun process_emptyString_returnsEmpty() {
        assertEquals("", OfflineTextProcessor.process(""))
    }

    @Test
    fun process_blankString_returnsEmpty() {
        assertEquals("", OfflineTextProcessor.process("   "))
    }

    @Test
    fun process_trimsLeadingAndTrailingWhitespace() {
        assertEquals("Hello world", OfflineTextProcessor.process("  hello world  "))
    }

    @Test
    fun process_capitalizesFirstLetter() {
        assertEquals("Hello world", OfflineTextProcessor.process("hello world"))
    }

    @Test
    fun process_firstLetterAlreadyCapitalized_unchanged() {
        assertEquals("Hello world", OfflineTextProcessor.process("Hello world"))
    }

    @Test
    fun process_capitalizesAfterPeriod() {
        assertEquals("Hello. World", OfflineTextProcessor.process("hello. world"))
    }

    @Test
    fun process_capitalizesAfterExclamation() {
        assertEquals("Hello! World", OfflineTextProcessor.process("hello! world"))
    }

    @Test
    fun process_capitalizesAfterQuestionMark() {
        assertEquals("Hello? World", OfflineTextProcessor.process("hello? world"))
    }

    @Test
    fun process_multipleSentences_capitalizesEach() {
        assertEquals(
            "Hello world. How are you? I am fine! Thanks",
            OfflineTextProcessor.process("hello world. how are you? i am fine! thanks")
        )
    }

    @Test
    fun process_noCapitalizationAfterComma() {
        assertEquals("Hello, world", OfflineTextProcessor.process("hello, world"))
    }

    @Test
    fun process_noCapitalizationAfterMidWordPeriod() {
        assertEquals("Hello world", OfflineTextProcessor.process("hello world"))
    }

    @Test
    fun process_periodWithoutFollowingSpace_unchanged() {
        assertEquals("Hello.world", OfflineTextProcessor.process("hello.world"))
    }

    @Test
    fun process_standaloneILowercase_becomesUppercase() {
        assertEquals("I am here", OfflineTextProcessor.process("i am here"))
    }

    @Test
    fun process_standaloneIAfterPunctuation_becomesUppercase() {
        assertEquals("Hello. I am here", OfflineTextProcessor.process("hello. i am here"))
    }

    @Test
    fun process_standaloneIAtEnd_becomesUppercase() {
        assertEquals("It is I", OfflineTextProcessor.process("it is i"))
    }

    @Test
    fun process_standaloneIWithMultipleSpaces_becomesUppercaseAndSpacesCollapsed() {
        assertEquals("Hello I am here", OfflineTextProcessor.process("hello  i  am here"))
    }

    @Test
    fun process_contractionIve_notChanged() {
        assertEquals("I've got it", OfflineTextProcessor.process("i've got it"))
    }

    @Test
    fun process_contractionIll_notChanged() {
        assertEquals("I'll go", OfflineTextProcessor.process("i'll go"))
    }

    @Test
    fun process_wordStartingWithI_notChanged() {
        assertEquals("It is fine", OfflineTextProcessor.process("it is fine"))
    }

    @Test
    fun process_uppercaseIStandalone_unchanged() {
        assertEquals("I am here", OfflineTextProcessor.process("I am here"))
    }

    @Test
    fun process_collapseDoubleSpaces() {
        assertEquals("Hello world", OfflineTextProcessor.process("hello  world"))
    }

    @Test
    fun process_collapseManySpaces() {
        assertEquals("Hello world", OfflineTextProcessor.process("hello     world"))
    }

    @Test
    fun process_preservesSingleNewline() {
        assertEquals("Hello\nworld", OfflineTextProcessor.process("hello\nworld"))
    }

    @Test
    fun process_preservesDoubleNewline() {
        assertEquals("Hello\n\nworld", OfflineTextProcessor.process("hello\n\nworld"))
    }

    @Test
    fun process_fullPipeline() {
        assertEquals(
            "Hello world. I am fine. How are you?",
            OfflineTextProcessor.process("  hello world.  i am fine.  how are you?  ")
        )
    }

    @Test
    fun process_abbreviationsNotTreatedAsSentenceEnd() {
        assertEquals("E.g. note this", OfflineTextProcessor.process("e.g. note this"))
        assertEquals("Ask dr. smith today", OfflineTextProcessor.process("ask dr. smith today"))
        assertEquals("Use etc. sparingly", OfflineTextProcessor.process("use etc. sparingly"))
    }

    @Test
    fun process_domainsAndFilesNotTreatedAsSentenceEnd() {
        assertEquals("Visit example.com. now please", OfflineTextProcessor.process("visit example.com. now please"))
        assertEquals("Open file.png. then edit", OfflineTextProcessor.process("open file.png. then edit"))
    }

    @Test
    fun process_realSentenceEndStillCapitalizes() {
        assertEquals("Hello. World", OfflineTextProcessor.process("hello. world"))
        assertEquals("3.5 is the version. Next", OfflineTextProcessor.process("3.5 is the version. next"))
    }
}
